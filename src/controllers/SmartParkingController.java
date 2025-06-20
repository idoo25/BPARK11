package controllers;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import entities.ParkingOrder;
import entities.ParkingSubscriber;

/**
 * Smart Parking Allocation System with enhanced algorithms
 * Combines all existing ParkingController functionality with new smart features
 */
public class SmartParkingController {
    
    // Configuration constants
    private static final int TOTAL_PARKING_SPOTS = 100;
    private static final double AVAILABILITY_THRESHOLD = 0.4; // 40% rule
    private static final int PREFERRED_WINDOW_HOURS = 8;
    private static final int STANDARD_BOOKING_HOURS = 4;
    private static final int MINIMUM_SPONTANEOUS_HOURS = 2;
    private static final int TIME_SLOT_MINUTES = 15; // 15-minute precision
    private static final int DISPLAY_WINDOW_HOURS = 1; // ±1 hour around selected time
    private static final int MINIMUM_EXTENSION_HOURS = 2;
    private static final int MAXIMUM_EXTENSION_HOURS = 4;
    
    protected Connection conn;
    public int successFlag;

    public SmartParkingController(String dbname, String pass) {
        String connectPath = "jdbc:mysql://localhost/" + dbname + "?serverTimezone=Asia/Jerusalem";
        connectToDB(connectPath, pass);
    }

    public Connection getConnection() {
        return conn;
    }

    public void connectToDB(String path, String pass) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            System.out.println("Driver definition succeed");
        } catch (Exception ex) {
            System.out.println("Driver definition failed");
        }

        try {
            conn = DriverManager.getConnection(path, "root", pass);
            System.out.println("SQL connection succeed");
            successFlag = 1;
        } catch (SQLException ex) {
            System.out.println("SQLException: " + ex.getMessage());
            System.out.println("SQLState: " + ex.getSQLState());
            System.out.println("VendorError: " + ex.getErrorCode());
            successFlag = 2;
        }
    }

    // ========== CORE DATA STRUCTURES ==========
    
    /**
     * Represents a 15-minute time slot availability
     */
    public static class TimeSlot {
        public LocalDateTime startTime;
        public boolean isAvailable;
        public int availableSpots;
        public boolean meetsFortyPercentRule;
        
        public TimeSlot(LocalDateTime startTime, boolean isAvailable, int availableSpots, boolean meetsFortyPercentRule) {
            this.startTime = startTime;
            this.isAvailable = isAvailable;
            this.availableSpots = availableSpots;
            this.meetsFortyPercentRule = meetsFortyPercentRule;
        }
        
        public String getFormattedTime() {
            return startTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        }
    }
    
    /**
     * Represents parking spot availability window
     */
    public static class SpotAvailability {
        public int spotId;
        public LocalDateTime availableFrom;
        public LocalDateTime availableUntil;
        public long availabilityDurationHours;
        
        public SpotAvailability(int spotId, LocalDateTime from, LocalDateTime until) {
            this.spotId = spotId;
            this.availableFrom = from;
            this.availableUntil = until;
            this.availabilityDurationHours = Duration.between(from, until).toHours();
        }
        
        public boolean hasEightHourWindow(LocalDateTime bookingStart) {
            LocalDateTime eightHourEnd = bookingStart.plusHours(PREFERRED_WINDOW_HOURS);
            return !bookingStart.isBefore(availableFrom) && !eightHourEnd.isAfter(availableUntil);
        }
        
        public boolean canAccommodateBooking(LocalDateTime bookingStart, LocalDateTime bookingEnd) {
            return !bookingStart.isBefore(availableFrom) && !bookingEnd.isAfter(availableUntil);
        }
    }

    // ========== ALL EXISTING PARKINGCONTROLLER METHODS ==========
    
    public String checkLogin(String userName, String password) {
        String qry = "SELECT UserTypeEnum FROM users WHERE UserName = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("UserTypeEnum");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error checking login: " + e.getMessage());
        }
        return "None";
    }
    
    public int getAvailableParkingSpots() {
        String qry = "SELECT COUNT(*) as available FROM ParkingSpot WHERE isOccupied = false";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("available");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting available spots: " + e.getMessage());
        }
        return 0;
    }
    
    public ParkingSubscriber getUserInfo(String userName) {
        String qry = "SELECT * FROM users WHERE UserName = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    ParkingSubscriber user = new ParkingSubscriber();
                    user.setSubscriberID(rs.getInt("User_ID"));
                    user.setFirstName(rs.getString("Name"));
                    user.setPhoneNumber(rs.getString("Phone"));
                    user.setEmail(rs.getString("Email"));
                    user.setCarNumber(rs.getString("CarNum"));
                    user.setSubscriberCode(userName);
                    user.setUserType(rs.getString("UserTypeEnum"));
                    return user;
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting user info: " + e.getMessage());
        }
        return null;
    }
    
    public String makeReservation(String userName, String reservationDateStr) {
        if (!canMakeReservation()) {
            return "Not enough available spots for reservation (need 40% available)";
        }

        try {
            Date reservationDate = Date.valueOf(reservationDateStr);
            LocalDate today = LocalDate.now();
            LocalDate resDate = reservationDate.toLocalDate();
            
            if (resDate.isBefore(today.plusDays(1)) || resDate.isAfter(today.plusDays(7))) {
                return "Reservation must be between 24 hours and 7 days in advance";
            }

            int userID = getUserID(userName);
            if (userID == -1) {
                return "User not found";
            }

            int parkingSpotID = getAvailableParkingSpotID();
            if (parkingSpotID == -1) {
                return "No available parking spots";
            }

            String qry = "INSERT INTO Reservations (User_ID, parking_ID, reservation_Date, Date_Of_Placing_Order, statusEnum) VALUES (?, ?, ?, ?, 'active')";
            
            try (PreparedStatement stmt = conn.prepareStatement(qry, PreparedStatement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, userID);
                stmt.setInt(2, parkingSpotID);
                stmt.setDate(3, reservationDate);
                stmt.setDate(4, Date.valueOf(LocalDate.now()));
                stmt.executeUpdate();
                
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        int reservationCode = generatedKeys.getInt(1);
                        return "Reservation confirmed. Confirmation code: " + reservationCode;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            return "Invalid date format. Use YYYY-MM-DD";
        } catch (SQLException e) {
            System.out.println("Error making reservation: " + e.getMessage());
            return "Reservation failed";
        }
        return "Reservation failed";
    }
    
    public String enterParking(String userName) {
        int userID = getUserID(userName);
        if (userID == -1) {
            return "Invalid user code";
        }

        if (getAvailableParkingSpots() <= 0) {
            return "No parking spots available";
        }

        int spotID = getAvailableParkingSpotID();
        if (spotID == -1) {
            return "No available parking spot found";
        }

        int parkingCode = generateParkingCode();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime estimatedEnd = now.plusHours(4);

        String qry = "INSERT INTO ParkingInfo (ParkingSpot_ID, User_ID, Date, Code, Actual_start_time, Estimated_start_time, Estimated_end_time, IsOrderedEnum, IsLate, IsExtended) VALUES (?, ?, ?, ?, ?, ?, ?, 'not ordered', false, false)";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setInt(1, spotID);
            stmt.setInt(2, userID);
            stmt.setDate(3, Date.valueOf(now.toLocalDate()));
            stmt.setInt(4, parkingCode);
            stmt.setTime(5, Time.valueOf(now.toLocalTime()));
            stmt.setTime(6, Time.valueOf(now.toLocalTime()));
            stmt.setTime(7, Time.valueOf(estimatedEnd.toLocalTime()));
            stmt.executeUpdate();

            updateParkingSpotStatus(spotID, true);
            
            return "Entry successful. Parking code: " + parkingCode + ". Spot: " + spotID;
        } catch (SQLException e) {
            System.out.println("Error handling entry: " + e.getMessage());
            return "Entry failed";
        }
    }
    
    public String enterParkingWithReservation(int reservationCode) {
        String checkQry = "SELECT r.*, u.User_ID FROM Reservations r JOIN users u ON r.User_ID = u.User_ID WHERE r.Reservation_code = ? AND r.statusEnum = 'active'";
        
        try (PreparedStatement stmt = conn.prepareStatement(checkQry)) {
            stmt.setInt(1, reservationCode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Date reservationDate = rs.getDate("reservation_Date");
                    int userID = rs.getInt("User_ID");
                    int parkingSpotID = rs.getInt("parking_ID");
                    
                    LocalDate today = LocalDate.now();
                    if (!reservationDate.toLocalDate().equals(today)) {
                        if (reservationDate.toLocalDate().isBefore(today)) {
                            cancelReservation(reservationCode);
                            return "Reservation expired";
                        } else {
                            return "Reservation is for future date";
                        }
                    }

                    if (!isParkingSpotAvailable(parkingSpotID)) {
                        parkingSpotID = getAvailableParkingSpotID();
                        if (parkingSpotID == -1) {
                            return "No available parking spots found";
                        }
                    }

                    int parkingCode = generateParkingCode();
                    LocalDateTime now = LocalDateTime.now();
                    LocalDateTime estimatedEnd = now.plusHours(4);

                    String insertQry = "INSERT INTO ParkingInfo (ParkingSpot_ID, User_ID, Date, Code, Actual_start_time, Estimated_start_time, Estimated_end_time, IsOrderedEnum, IsLate, IsExtended) VALUES (?, ?, ?, ?, ?, ?, ?, 'ordered', false, false)";
                    
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertQry)) {
                        insertStmt.setInt(1, parkingSpotID);
                        insertStmt.setInt(2, userID);
                        insertStmt.setDate(3, Date.valueOf(now.toLocalDate()));
                        insertStmt.setInt(4, parkingCode);
                        insertStmt.setTime(5, Time.valueOf(now.toLocalTime()));
                        insertStmt.setTime(6, Time.valueOf(now.toLocalTime()));
                        insertStmt.setTime(7, Time.valueOf(estimatedEnd.toLocalTime()));
                        insertStmt.executeUpdate();

                        updateParkingSpotStatus(parkingSpotID, true);
                        updateReservationStatus(reservationCode, "expire");
                        
                        return "Entry successful with reservation. Parking code: " + parkingCode + ". Spot: " + parkingSpotID;
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Error handling reservation entry: " + e.getMessage());
        }
        return "Invalid or expired reservation code";
    }
    
    public String registerNewSubscriber(String name, String phone, String email, String carNumber, String userName) {
        if (name == null || name.trim().isEmpty()) {
            return "Name is required";
        }
        if (phone == null || phone.trim().isEmpty()) {
            return "Phone number is required";
        }
        if (email == null || email.trim().isEmpty()) {
            return "Email is required";
        }
        if (userName == null || userName.trim().isEmpty()) {
            return "Username is required";
        }
        
        String checkQry = "SELECT COUNT(*) FROM users WHERE UserName = ?";
        
        try (PreparedStatement checkStmt = conn.prepareStatement(checkQry)) {
            checkStmt.setString(1, userName);
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return "Username already exists. Please choose a different username.";
                }
            }
        } catch (SQLException e) {
            System.out.println("Error checking username: " + e.getMessage());
            return "Error checking username availability";
        }
        
        String insertQry = "INSERT INTO users (UserName, Name, Phone, Email, CarNum, UserTypeEnum) VALUES (?, ?, ?, ?, ?, 'sub')";
        
        try (PreparedStatement stmt = conn.prepareStatement(insertQry)) {
            stmt.setString(1, userName);
            stmt.setString(2, name);
            stmt.setString(3, phone);
            stmt.setString(4, email);
            stmt.setString(5, carNumber);
            
            int rowsInserted = stmt.executeUpdate();
            if (rowsInserted > 0) {
                System.out.println("New subscriber registered: " + userName);
                return "SUCCESS:Subscriber registered successfully. Username: " + userName;
            }
        } catch (SQLException e) {
            System.out.println("Registration failed: " + e.getMessage());
            return "Registration failed: " + e.getMessage();
        }
        
        return "Registration failed: Unknown error";
    }
    
    public String generateUniqueUsername(String baseName) {
        String cleanName = baseName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        
        if (isUsernameAvailable(cleanName)) {
            return cleanName;
        }
        
        for (int i = 1; i <= 999; i++) {
            String candidate = cleanName + i;
            if (isUsernameAvailable(candidate)) {
                return candidate;
            }
        }
        
        return cleanName + System.currentTimeMillis() % 10000;
    }
    
    public String exitParking(String parkingCodeStr) {
        try {
            int parkingCode = Integer.parseInt(parkingCodeStr);
            String qry = "SELECT pi.*, ps.ParkingSpot_ID FROM ParkingInfo pi JOIN ParkingSpot ps ON pi.ParkingSpot_ID = ps.ParkingSpot_ID WHERE pi.Code = ? AND pi.Actual_end_time IS NULL";
            
            try (PreparedStatement stmt = conn.prepareStatement(qry)) {
                stmt.setInt(1, parkingCode);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int parkingInfoID = rs.getInt("ParkingInfo_ID");
                        int spotID = rs.getInt("ParkingSpot_ID");
                        Time estimatedEndTime = rs.getTime("Estimated_end_time");
                        int userID = rs.getInt("User_ID");
                        
                        LocalTime now = LocalTime.now();
                        LocalTime estimatedEnd = estimatedEndTime.toLocalTime();
                        
                        boolean isLate = now.isAfter(estimatedEnd);
                        
                        String updateQry = "UPDATE ParkingInfo SET Actual_end_time = ?, IsLate = ? WHERE ParkingInfo_ID = ?";
                        
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateQry)) {
                            updateStmt.setTime(1, Time.valueOf(now));
                            updateStmt.setBoolean(2, isLate);
                            updateStmt.setInt(3, parkingInfoID);
                            updateStmt.executeUpdate();
                            
                            updateParkingSpotStatus(spotID, false);
                            
                            if (isLate) {
                                sendLateExitNotification(userID);
                                return "Exit successful. You were late - notification sent to your email/SMS";
                            }
                            
                            return "Exit successful. Thank you for using ParkB!";
                        }
                    }
                }
            }
        } catch (NumberFormatException e) {
            return "Invalid parking code format";
        } catch (SQLException e) {
            System.out.println("Error handling exit: " + e.getMessage());
        }
        return "Invalid parking code or already exited";
    }
    
    public String extendParkingTime(String parkingCodeStr, int additionalHours) {
        if (additionalHours < 1 || additionalHours > 4) {
            return "Can only extend parking by 1-4 hours";
        }
        
        try {
            int parkingCode = Integer.parseInt(parkingCodeStr);
            String qry = "SELECT pi.* FROM ParkingInfo pi WHERE pi.Code = ? AND pi.Actual_end_time IS NULL";
            
            try (PreparedStatement stmt = conn.prepareStatement(qry)) {
                stmt.setInt(1, parkingCode);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        Time currentEstimatedEnd = rs.getTime("Estimated_end_time");
                        LocalTime newEstimatedEnd = currentEstimatedEnd.toLocalTime().plusHours(additionalHours);
                        
                        String updateQry = "UPDATE ParkingInfo SET Estimated_end_time = ?, IsExtended = true WHERE Code = ?";
                        
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateQry)) {
                            updateStmt.setTime(1, Time.valueOf(newEstimatedEnd));
                            updateStmt.setInt(2, parkingCode);
                            updateStmt.executeUpdate();
                            
                            return "Parking time extended by " + additionalHours + " hours until " + newEstimatedEnd;
                        }
                    }
                }
            }
        } catch (NumberFormatException e) {
            return "Invalid parking code format";
        } catch (SQLException e) {
            System.out.println("Error extending parking time: " + e.getMessage());
        }
        return "Invalid parking code or parking session not active";
    }
    
    public String sendLostParkingCode(String userName) {
        String qry = "SELECT pi.Code, u.Email, u.Phone FROM ParkingInfo pi JOIN users u ON pi.User_ID = u.User_ID WHERE u.UserName = ? AND pi.Actual_end_time IS NULL";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int parkingCode = rs.getInt("Code");
                    String email = rs.getString("Email");
                    String phone = rs.getString("Phone");
                    
                    System.out.println("Sending parking code " + parkingCode + " to email: " + email + " and phone: " + phone);
                    
                    return String.valueOf(parkingCode);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error sending lost code: " + e.getMessage());
        }
        return "No active parking session found";
    }
    
    public ArrayList<ParkingOrder> getParkingHistory(String userName) {
        ArrayList<ParkingOrder> history = new ArrayList<>();
        String qry = "SELECT pi.*, ps.ParkingSpot_ID FROM ParkingInfo pi JOIN users u ON pi.User_ID = u.User_ID JOIN ParkingSpot ps ON pi.ParkingSpot_ID = ps.ParkingSpot_ID WHERE u.UserName = ? ORDER BY pi.Date DESC, pi.Actual_start_time DESC";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ParkingOrder order = new ParkingOrder();
                    order.setOrderID(rs.getInt("ParkingInfo_ID"));
                    order.setParkingCode(String.valueOf(rs.getInt("Code")));
                    order.setOrderType(rs.getString("IsOrderedEnum"));
                    order.setSpotNumber("Spot " + rs.getInt("ParkingSpot_ID"));
                    
                    Date date = rs.getDate("Date");
                    Time startTime = rs.getTime("Actual_start_time");
                    Time endTime = rs.getTime("Actual_end_time");
                    Time estimatedEnd = rs.getTime("Estimated_end_time");
                    
                    if (date != null && startTime != null) {
                        order.setEntryTime(LocalDateTime.of(date.toLocalDate(), startTime.toLocalTime()));
                    }
                    if (date != null && endTime != null) {
                        order.setExitTime(LocalDateTime.of(date.toLocalDate(), endTime.toLocalTime()));
                    }
                    if (date != null && estimatedEnd != null) {
                        order.setExpectedExitTime(LocalDateTime.of(date.toLocalDate(), estimatedEnd.toLocalTime()));
                    }
                    
                    order.setLate(rs.getBoolean("IsLate"));
                    order.setExtended(rs.getBoolean("IsExtended"));
                    order.setStatus(endTime != null ? "Completed" : "Active");
                    
                    history.add(order);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting parking history: " + e.getMessage());
        }
        return history;
    }
    
    public ArrayList<ParkingOrder> getActiveParkings() {
        ArrayList<ParkingOrder> activeParkings = new ArrayList<>();
        String qry = "SELECT pi.*, u.Name, ps.ParkingSpot_ID FROM ParkingInfo pi JOIN users u ON pi.User_ID = u.User_ID JOIN ParkingSpot ps ON pi.ParkingSpot_ID = ps.ParkingSpot_ID WHERE pi.Actual_end_time IS NULL ORDER BY pi.Actual_start_time";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ParkingOrder order = new ParkingOrder();
                    order.setOrderID(rs.getInt("ParkingInfo_ID"));
                    order.setParkingCode(String.valueOf(rs.getInt("Code")));
                    order.setOrderType(rs.getString("IsOrderedEnum"));
                    order.setSubscriberName(rs.getString("Name"));
                    order.setSpotNumber("Spot " + rs.getInt("ParkingSpot_ID"));
                    
                    Date date = rs.getDate("Date");
                    Time startTime = rs.getTime("Actual_start_time");
                    Time estimatedEnd = rs.getTime("Estimated_end_time");
                    
                    if (date != null && startTime != null) {
                        order.setEntryTime(LocalDateTime.of(date.toLocalDate(), startTime.toLocalTime()));
                    }
                    if (date != null && estimatedEnd != null) {
                        order.setExpectedExitTime(LocalDateTime.of(date.toLocalDate(), estimatedEnd.toLocalTime()));
                    }
                    
                    order.setStatus("Active");
                    activeParkings.add(order);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting active parkings: " + e.getMessage());
        }
        return activeParkings;
    }
    
    public String updateSubscriberInfo(String updateData) {
        String[] data = updateData.split(",");
        if (data.length != 3) {
            return "Invalid update data format";
        }
        
        String userName = data[0];
        String phone = data[1];
        String email = data[2];
        
        String qry = "UPDATE users SET Phone = ?, Email = ? WHERE UserName = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, phone);
            stmt.setString(2, email);
            stmt.setString(3, userName);
            
            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated > 0) {
                return "Subscriber information updated successfully";
            }
        } catch (SQLException e) {
            System.out.println("Error updating subscriber info: " + e.getMessage());
        }
        return "Failed to update subscriber information";
    }
    
    public String cancelReservation(int reservationCode) {
        String qry = "UPDATE Reservations SET statusEnum = 'cancelled' WHERE Reservation_code = ? AND statusEnum = 'active'";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setInt(1, reservationCode);
            int rowsUpdated = stmt.executeUpdate();
            
            if (rowsUpdated > 0) {
                return "Reservation cancelled successfully";
            }
        } catch (SQLException e) {
            System.out.println("Error cancelling reservation: " + e.getMessage());
        }
        return "Reservation not found or already cancelled";
    }
    
    public void logoutUser(String userName) {
        System.out.println("User logged out: " + userName);
    }
    
    public void initializeParkingSpots() {
        try {
            String checkQry = "SELECT COUNT(*) FROM ParkingSpot";
            try (PreparedStatement stmt = conn.prepareStatement(checkQry)) {
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        String insertQry = "INSERT INTO ParkingSpot (isOccupied) VALUES (false)";
                        try (PreparedStatement insertStmt = conn.prepareStatement(insertQry)) {
                            for (int i = 1; i <= TOTAL_PARKING_SPOTS; i++) {
                                insertStmt.executeUpdate();
                            }
                        }
                        System.out.println("Successfully initialized " + TOTAL_PARKING_SPOTS + " parking spots");
                    } else {
                        System.out.println("Parking spots already exist: " + rs.getInt(1) + " spots found");
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Error initializing parking spots: " + e.getMessage());
        }
    }

    // ========== NEW SMART FEATURES ==========
    
    /**
     * Get available 15-minute time slots for a specific date and preferred time
     */
    public List<TimeSlot> getAvailableTimeSlots(LocalDate date, LocalTime preferredTime) {
        List<TimeSlot> timeSlots = new ArrayList<>();
        
        try {
            if (!dateHasValidBookingWindow(date)) {
                return timeSlots;
            }
            
            LocalDateTime preferredDateTime = LocalDateTime.of(date, preferredTime);
            LocalDateTime startRange = preferredDateTime.minusHours(DISPLAY_WINDOW_HOURS);
            LocalDateTime endRange = preferredDateTime.plusHours(DISPLAY_WINDOW_HOURS);
            
            LocalDateTime currentSlot = startRange;
            while (!currentSlot.isAfter(endRange)) {
                LocalDateTime bookingEnd = currentSlot.plusHours(STANDARD_BOOKING_HOURS);
                boolean hasValidWindow = hasValidFourHourWindow(currentSlot, bookingEnd);
                int availableSpots = countAvailableSpotsForWindow(currentSlot, bookingEnd);
                boolean meetsFortyPercent = availableSpots >= (TOTAL_PARKING_SPOTS * AVAILABILITY_THRESHOLD);
                
                timeSlots.add(new TimeSlot(
                    currentSlot, 
                    hasValidWindow && meetsFortyPercent, 
                    availableSpots,
                    meetsFortyPercent
                ));
                
                currentSlot = currentSlot.plusMinutes(TIME_SLOT_MINUTES);
            }
            
        } catch (Exception e) {
            System.out.println("Error getting available time slots: " + e.getMessage());
        }
        
        return timeSlots;
    }
    
    /**
     * Make a pre-booking reservation with 15-minute precision
     */
    public String makePreBooking(String userName, String dateTimeStr) {
        try {
            LocalDateTime bookingStart = parseDateTime(dateTimeStr);
            LocalDateTime bookingEnd = bookingStart.plusHours(STANDARD_BOOKING_HOURS);
            
            if (bookingStart.getMinute() % TIME_SLOT_MINUTES != 0) {
                return "Booking time must be in 15-minute intervals (00, 15, 30, 45)";
            }
            
            LocalDateTime now = LocalDateTime.now();
            if (bookingStart.isBefore(now.plusHours(24))) {
                return "Pre-booking must be at least 24 hours in advance";
            }
            if (bookingStart.isAfter(now.plusDays(7))) {
                return "Pre-booking cannot be more than 7 days in advance";
            }
            
            if (!hasValidFourHourWindow(bookingStart, bookingEnd)) {
                return "No available 4-hour window with required capacity at selected time";
            }
            
            int optimalSpotId = findOptimalSpotForPreBooking(bookingStart, bookingEnd);
            if (optimalSpotId == -1) {
                return "No optimal parking spot available for selected time";
            }
            
            int userID = getUserID(userName);
            if (userID == -1) {
                return "User not found";
            }
            
            return createReservationWithDateTime(userID, optimalSpotId, bookingStart, bookingEnd, "prebooking");
            
        } catch (Exception e) {
            System.out.println("Error making pre-booking: " + e.getMessage());
            return "Pre-booking failed: " + e.getMessage();
        }
    }
    
    /**
     * Handle spontaneous parking entry
     */
    public String enterSpontaneousParking(String userName) {
        LocalDateTime now = LocalDateTime.now();
        
        try {
            SpotAllocation allocation = findOptimalSpontaneousAllocation(now);
            if (allocation == null) {
                return "No parking spots available for spontaneous parking (minimum 2 hours required)";
            }
            
            int userID = getUserID(userName);
            if (userID == -1) {
                return "Invalid user code";
            }
            
            int parkingCode = generateParkingCode();
            LocalDateTime sessionEnd = now.plusHours(allocation.allocatedHours);
            
            String insertQuery = """
                INSERT INTO ParkingInfo 
                (ParkingSpot_ID, User_ID, Date, Code, Actual_start_time, Estimated_start_time, 
                 Estimated_end_time, IsOrderedEnum, IsLate, IsExtended) 
                VALUES (?, ?, ?, ?, ?, ?, ?, 'not ordered', false, false)
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(insertQuery)) {
                stmt.setInt(1, allocation.spotId);
                stmt.setInt(2, userID);
                stmt.setDate(3, Date.valueOf(now.toLocalDate()));
                stmt.setInt(4, parkingCode);
                stmt.setTime(5, Time.valueOf(now.toLocalTime()));
                stmt.setTime(6, Time.valueOf(now.toLocalTime()));
                stmt.setTime(7, Time.valueOf(sessionEnd.toLocalTime()));
                stmt.executeUpdate();
                
                updateSpotOccupancy(allocation.spotId, true);
                
                return String.format("Spontaneous parking successful! Code: %d, Spot: %d, Duration: %d hours%s",
                                   parkingCode, allocation.spotId, allocation.allocatedHours,
                                   allocation.hasEightHourWindow ? " (8+ hour window)" : "");
            }
            
        } catch (Exception e) {
            System.out.println("Error in spontaneous parking: " + e.getMessage());
            return "Spontaneous parking failed: " + e.getMessage();
        }
    }
    
    /**
     * Request parking extension during the last hour
     */
    public String requestParkingExtension(String parkingCodeStr) {
        try {
            int parkingCode = Integer.parseInt(parkingCodeStr);
            
            String sessionQuery = """
                SELECT pi.*, ps.ParkingSpot_ID 
                FROM ParkingInfo pi 
                JOIN ParkingSpot ps ON pi.ParkingSpot_ID = ps.ParkingSpot_ID 
                WHERE pi.Code = ? AND pi.Actual_end_time IS NULL
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sessionQuery)) {
                stmt.setInt(1, parkingCode);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int spotId = rs.getInt("ParkingSpot_ID");
                        Time estimatedEndTime = rs.getTime("Estimated_end_time");
                        Date date = rs.getDate("Date");
                        
                        LocalDateTime currentEndTime = LocalDateTime.of(date.toLocalDate(), estimatedEndTime.toLocalTime());
                        LocalDateTime now = LocalDateTime.now();
                        
                        if (now.isBefore(currentEndTime.minusHours(1))) {
                            return "Extensions can only be requested during the last hour of parking";
                        }
                        
                        if (now.isAfter(currentEndTime)) {
                            return "Parking session has already ended";
                        }
                        
                        int maxExtensionHours = findMaximumExtension(spotId, currentEndTime);
                        if (maxExtensionHours < MINIMUM_EXTENSION_HOURS) {
                            return "No extension available - spot not free for minimum required time";
                        }
                        
                        LocalDateTime newEndTime = currentEndTime.plusHours(maxExtensionHours);
                        
                        String updateQuery = """
                            UPDATE ParkingInfo 
                            SET Estimated_end_time = ?, IsExtended = true 
                            WHERE Code = ?
                            """;
                        
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateQuery)) {
                            updateStmt.setTime(1, Time.valueOf(newEndTime.toLocalTime()));
                            updateStmt.setInt(2, parkingCode);
                            updateStmt.executeUpdate();
                            
                            return String.format("Extension successful! Parking extended by %d hours until %s",
                                               maxExtensionHours, newEndTime.format(DateTimeFormatter.ofPattern("HH:mm")));
                        }
                    }
                }
            }
            
        } catch (NumberFormatException e) {
            return "Invalid parking code format";
        } catch (Exception e) {
            System.out.println("Error requesting extension: " + e.getMessage());
        }
        
        return "Invalid parking code or parking session not found";
    }
    
    /**
     * Get parking system status
     */
    public String getSystemStatus() {
        try {
            int totalSpots = TOTAL_PARKING_SPOTS;
            int occupiedSpots = getCurrentlyOccupiedSpots();
            int availableSpots = totalSpots - occupiedSpots;
            
            return String.format("Smart Parking Status: %d total spots, %d occupied, %d available (%.1f%% available)",
                               totalSpots, occupiedSpots, availableSpots, 
                               (double) availableSpots / totalSpots * 100);
                               
        } catch (Exception e) {
            return "Error getting system status: " + e.getMessage();
        }
    }

    // ========== HELPER METHODS ==========
    
    private static class SpotAllocation {
        int spotId;
        int allocatedHours;
        boolean hasEightHourWindow;
        
        SpotAllocation(int spotId, int allocatedHours, boolean hasEightHourWindow) {
            this.spotId = spotId;
            this.allocatedHours = allocatedHours;
            this.hasEightHourWindow = hasEightHourWindow;
        }
    }
    
    private boolean dateHasValidBookingWindow(LocalDate date) {
        try {
            LocalDateTime dayStart = LocalDateTime.of(date, LocalTime.of(0, 0));
            LocalDateTime dayEnd = LocalDateTime.of(date, LocalTime.of(23, 45));
            
            LocalDateTime currentTime = dayStart;
            while (!currentTime.isAfter(dayEnd.minusHours(STANDARD_BOOKING_HOURS))) {
                LocalDateTime windowEnd = currentTime.plusHours(STANDARD_BOOKING_HOURS);
                if (hasValidFourHourWindow(currentTime, windowEnd)) {
                    return true;
                }
                currentTime = currentTime.plusMinutes(TIME_SLOT_MINUTES);
            }
        } catch (Exception e) {
            System.out.println("Error checking date validity: " + e.getMessage());
        }
        return false;
    }
    
    private boolean hasValidFourHourWindow(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            int availableSpots = countAvailableSpotsForWindow(startTime, endTime);
            return availableSpots >= (TOTAL_PARKING_SPOTS * AVAILABILITY_THRESHOLD);
        } catch (Exception e) {
            System.out.println("Error checking four-hour window: " + e.getMessage());
            return false;
        }
    }
    
    private int countAvailableSpotsForWindow(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            int occupiedSpots = getCurrentlyOccupiedSpots();
            int reservedSpots = countReservationOverlaps(startTime, endTime);
            return Math.max(0, TOTAL_PARKING_SPOTS - occupiedSpots - reservedSpots);
        } catch (Exception e) {
            System.out.println("Error counting available spots: " + e.getMessage());
            return 0;
        }
    }
    
    private int findOptimalSpotForPreBooking(LocalDateTime bookingStart, LocalDateTime bookingEnd) {
        try {
            List<Integer> availableSpots = getAllAvailableSpots(bookingStart, bookingEnd);
            
            if (availableSpots.isEmpty()) {
                return -1;
            }
            
            // Return first available spot (simple allocation)
            return availableSpots.get(0);
            
        } catch (Exception e) {
            System.out.println("Error finding optimal spot: " + e.getMessage());
            return -1;
        }
    }
    
    private SpotAllocation findOptimalSpontaneousAllocation(LocalDateTime startTime) {
        try {
            for (int hours = STANDARD_BOOKING_HOURS; hours >= MINIMUM_SPONTANEOUS_HOURS; hours--) {
                LocalDateTime endTime = startTime.plusHours(hours);
                List<Integer> availableSpots = getAllAvailableSpots(startTime, endTime);
                
                if (!availableSpots.isEmpty()) {
                    return new SpotAllocation(availableSpots.get(0), hours, hours >= PREFERRED_WINDOW_HOURS);
                }
            }
            
            return null;
            
        } catch (Exception e) {
            System.out.println("Error finding spontaneous allocation: " + e.getMessage());
            return null;
        }
    }
    
    private List<Integer> getAllAvailableSpots(LocalDateTime startTime, LocalDateTime endTime) throws SQLException {
        List<Integer> availableSpots = new ArrayList<>();
        
        String spotsQuery = "SELECT ParkingSpot_ID FROM ParkingSpot WHERE isOccupied = false ORDER BY ParkingSpot_ID";
        
        try (PreparedStatement stmt = conn.prepareStatement(spotsQuery)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int spotId = rs.getInt("ParkingSpot_ID");
                    if (isSpotAvailableForPeriod(spotId, startTime, endTime)) {
                        availableSpots.add(spotId);
                    }
                }
            }
        }
        
        return availableSpots;
    }
    
    private boolean isSpotAvailableForPeriod(int spotId, LocalDateTime startTime, LocalDateTime endTime) throws SQLException {
        String conflictQuery = """
            SELECT COUNT(*) FROM Reservations 
            WHERE assigned_parking_spot_id = ? 
            AND statusEnum = 'active'
            AND NOT (reservation_Date < ? OR reservation_Date > ?)
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(conflictQuery)) {
            stmt.setInt(1, spotId);
            stmt.setDate(2, Date.valueOf(endTime.toLocalDate()));
            stmt.setDate(3, Date.valueOf(startTime.toLocalDate()));
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) == 0;
                }
            }
        }
        
        return false;
    }
    
    private int findMaximumExtension(int spotId, LocalDateTime currentEndTime) {
        try {
            for (int hours = MAXIMUM_EXTENSION_HOURS; hours >= MINIMUM_EXTENSION_HOURS; hours--) {
                LocalDateTime testEndTime = currentEndTime.plusHours(hours);
                if (isSpotAvailableForPeriod(spotId, currentEndTime, testEndTime)) {
                    return hours;
                }
            }
        } catch (Exception e) {
            System.out.println("Error finding maximum extension: " + e.getMessage());
        }
        return 0;
    }
    
    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            if (dateTimeStr.contains("T")) {
                return LocalDateTime.parse(dateTimeStr);
            } else if (dateTimeStr.contains(" ")) {
                return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } else {
                throw new IllegalArgumentException("Unsupported DATETIME format: " + dateTimeStr);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid DATETIME format: " + dateTimeStr + ". Use 'YYYY-MM-DD HH:MM:SS' or 'YYYY-MM-DDTHH:MM'");
        }
    }
    
    private String createReservationWithDateTime(int userID, int spotId, LocalDateTime startTime, LocalDateTime endTime, String type) throws SQLException {
        String insertQuery = """
            INSERT INTO Reservations 
            (User_ID, parking_ID, reservation_Date, Date_Of_Placing_Order, statusEnum, assigned_parking_spot_id) 
            VALUES (?, ?, ?, NOW(), 'active', ?)
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(insertQuery, PreparedStatement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, userID);
            stmt.setInt(2, spotId);
            stmt.setDate(3, Date.valueOf(startTime.toLocalDate()));
            stmt.setInt(4, spotId);
            stmt.executeUpdate();
            
            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    int reservationCode = generatedKeys.getInt(1);
                    
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                    return String.format("%s successful! Code: %d, Spot: %d, Time: %s to %s",
                                       type.substring(0, 1).toUpperCase() + type.substring(1),
                                       reservationCode, spotId, 
                                       startTime.format(formatter), endTime.format(formatter));
                }
            }
        }
        
        return "Reservation creation failed";
    }
    
    private int countReservationOverlaps(LocalDateTime startTime, LocalDateTime endTime) throws SQLException {
        String query = """
            SELECT COUNT(DISTINCT assigned_parking_spot_id) 
            FROM Reservations 
            WHERE assigned_parking_spot_id IS NOT NULL 
            AND statusEnum = 'active'
            AND reservation_Date >= ? 
            AND reservation_Date <= ?
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setDate(1, Date.valueOf(startTime.toLocalDate()));
            stmt.setDate(2, Date.valueOf(endTime.toLocalDate()));
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        
        return 0;
    }
    
    private int getCurrentlyOccupiedSpots() throws SQLException {
        String query = "SELECT COUNT(*) FROM ParkingSpot WHERE isOccupied = true";
        
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }
    
    private int generateParkingCode() {
        Random random = new Random();
        return 100000 + random.nextInt(900000);
    }
    
    private int getUserID(String userName) {
        String qry = "SELECT User_ID FROM users WHERE UserName = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("User_ID");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting user ID: " + e.getMessage());
        }
        return -1;
    }
    
    private boolean canMakeReservation() {
        int availableSpots = getAvailableParkingSpots();
        return availableSpots >= (TOTAL_PARKING_SPOTS * AVAILABILITY_THRESHOLD);
    }
    
    private int getAvailableParkingSpotID() {
        String qry = "SELECT ParkingSpot_ID FROM ParkingSpot WHERE isOccupied = false LIMIT 1";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("ParkingSpot_ID");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting available spot ID: " + e.getMessage());
        }
        return -1;
    }
    
    private boolean isParkingSpotAvailable(int spotID) {
        String qry = "SELECT isOccupied FROM ParkingSpot WHERE ParkingSpot_ID = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setInt(1, spotID);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return !rs.getBoolean("isOccupied");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error checking spot availability: " + e.getMessage());
        }
        return false;
    }
    
    private void updateParkingSpotStatus(int spotID, boolean isOccupied) {
        String qry = "UPDATE ParkingSpot SET isOccupied = ? WHERE ParkingSpot_ID = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setBoolean(1, isOccupied);
            stmt.setInt(2, spotID);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Error updating parking spot status: " + e.getMessage());
        }
    }
    
    private void updateSpotOccupancy(int spotId, boolean isOccupied) throws SQLException {
        updateParkingSpotStatus(spotId, isOccupied);
    }
    
    private void updateReservationStatus(int reservationCode, String status) {
        String qry = "UPDATE Reservations SET statusEnum = ? WHERE Reservation_code = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, status);
            stmt.setInt(2, reservationCode);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Error updating reservation status: " + e.getMessage());
        }
    }
    
    private void sendLateExitNotification(int userID) {
        String qry = "SELECT Email, Phone, Name FROM users WHERE User_ID = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setInt(1, userID);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String email = rs.getString("Email");
                    String phone = rs.getString("Phone");
                    String name = rs.getString("Name");
                    
                    System.out.println("Sending late exit notification to " + name + 
                                     " at email: " + email + " and phone: " + phone);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error sending late notification: " + e.getMessage());
        }
    }
    
    private boolean isUsernameAvailable(String userName) {
        String checkQry = "SELECT COUNT(*) FROM users WHERE UserName = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(checkQry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) == 0;
                }
            }
        } catch (SQLException e) {
            System.out.println("Error checking username availability: " + e.getMessage());
        }
        
        return false;
    }
}