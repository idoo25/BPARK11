package controllers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Random;

import entities.ParkingOrder;
import entities.ParkingSubscriber;
import services.EmailService;

/**
 * Enhanced ParkingController with email notifications
 * Updated to work with unified parkinginfo table structure
 */
public class ParkingController {
    protected Connection conn;
    public int successFlag;
    private static final int TOTAL_PARKING_SPOTS = 100;
    private static final double RESERVATION_THRESHOLD = 0.4;
    
    /**
     * Role-based access control for all parking operations
     */
    public enum UserRole {
        SUBSCRIBER("sub"),
        ATTENDANT("emp"), 
        MANAGER("mng");
        
        private final String dbValue;
        
        UserRole(String dbValue) {
            this.dbValue = dbValue;
        }
        
        public String getDbValue() {
            return dbValue;
        }
        
        public static UserRole fromDbValue(String dbValue) {
            for (UserRole role : values()) {
                if (role.dbValue.equals(dbValue)) {
                    return role;
                }
            }
            return null;
        }
    }

    /**
     * Get user role from database
     */
    private UserRole getUserRole(String userName) {
        String qry = "SELECT UserTypeEnum FROM users WHERE UserName = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String userType = rs.getString("UserTypeEnum");
                    return UserRole.fromDbValue(userType);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting user role: " + e.getMessage());
        }
        return null;
    }

    /**
     * Check if user has required role for operation
     */
    private boolean hasRole(String userName, UserRole requiredRole) {
        UserRole userRole = getUserRole(userName);
        return userRole == requiredRole;
    }

    /**
     * Check if user has any of the required roles
     */
    private boolean hasAnyRole(String userName, UserRole... requiredRoles) {
        UserRole userRole = getUserRole(userName);
        if (userRole == null) return false;
        
        for (UserRole role : requiredRoles) {
            if (userRole == role) return true;
        }
        return false;
    }
    
    // Auto-cancellation service
    private SimpleAutoCancellationService autoCancellationService;

    public ParkingController(String dbname, String pass) {
        String connectPath = "jdbc:mysql://localhost/" + dbname + "?serverTimezone=IST";
        connectToDB(connectPath, pass);
        
        // Initialize auto-cancellation service after DB connection
        if (successFlag == 1) {
            this.autoCancellationService = new SimpleAutoCancellationService(this);
            startAutoCancellationService();
        }
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

    /**
     * Start the automatic reservation cancellation service
     */
    public void startAutoCancellationService() {
        if (autoCancellationService != null) {
            autoCancellationService.startService();
            System.out.println("✅ Auto-cancellation service started - monitoring preorder reservations");
        }
    }

    /**
     * Stop the automatic reservation cancellation service
     */
    public void stopAutoCancellationService() {
        if (autoCancellationService != null) {
            autoCancellationService.stopService();
            System.out.println("⛔ Auto-cancellation service stopped");
        }
    }

    /**
     * Cleanup method - call when shutting down the controller
     */
    public void shutdown() {
        if (autoCancellationService != null) {
            autoCancellationService.shutdown();
        }
    }

    // ========== ALL YOUR EXISTING METHODS UPDATED ==========
    
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

    /**
     * Gets user information by userName
     */
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

    /**
     * Gets the number of available parking spots
     */
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

    /**
     * Checks if reservation is possible (40% of spots must be available)
     */
    public boolean canMakeReservation() {
        int availableSpots = getAvailableParkingSpots();
        return availableSpots >= (TOTAL_PARKING_SPOTS * RESERVATION_THRESHOLD);
    }

    /**
     * Makes a parking reservation with specific DATE and TIME
     * Format: "YYYY-MM-DD HH:MM" or "YYYY-MM-DD HH:MM:SS"
     */
    public String makeReservation(String userName, String reservationDateTimeStr) {
        // Check if reservation is possible (40% rule)
        if (!canMakeReservation()) {
            return "Not enough available spots for reservation (need 40% available)";
        }

        try {
            // Parse the datetime string
            LocalDateTime reservationDateTime = parseDateTime(reservationDateTimeStr);
            
            // Validate reservation is within allowed time range (24 hours to 7 days)
            LocalDateTime now = LocalDateTime.now();
            if (reservationDateTime.isBefore(now.plusHours(24))) {
                return "Reservation must be at least 24 hours in advance";
            }
            if (reservationDateTime.isAfter(now.plusDays(7))) {
                return "Reservation cannot be more than 7 days in advance";
            }

            // Get user ID
            int userID = getUserID(userName);
            if (userID == -1) {
                return "User not found";
            }

            // Find available parking spot
            int parkingSpotID = getAvailableParkingSpotID();
            if (parkingSpotID == -1) {
                return "No available parking spots";
            }

            // Calculate end time (default 4 hours)
            LocalDateTime estimatedEndTime = reservationDateTime.plusHours(4);

            // Create reservation in parkinginfo table with statusEnum='preorder'
            String qry = """
                INSERT INTO parkinginfo 
                (ParkingSpot_ID, User_ID, Date_Of_Placing_Order, Estimated_start_time, 
                 Estimated_end_time, IsOrderedEnum, IsLate, IsExtended, statusEnum) 
                VALUES (?, ?, NOW(), ?, ?, 'yes', 'no', 'no', 'preorder')
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(qry, PreparedStatement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, parkingSpotID);
                stmt.setInt(2, userID);
                stmt.setTimestamp(3, Timestamp.valueOf(reservationDateTime));
                stmt.setTimestamp(4, Timestamp.valueOf(estimatedEndTime));
                stmt.executeUpdate();
                
                // Get the generated ParkingInfo_ID (this is our reservation code)
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        int reservationCode = generatedKeys.getInt(1);
                        System.out.println("New preorder reservation created: " + reservationCode + 
                                         " for " + reservationDateTime + " (15-min auto-cancel rule applies)");
                        
                        // Send email confirmation
                        ParkingSubscriber user = getUserInfo(userName);
                        if (user != null && user.getEmail() != null) {
                            String formattedDateTime = reservationDateTime.format(
                                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                            EmailService.sendReservationConfirmation(
                                user.getEmail(), user.getFirstName(), 
                                String.valueOf(reservationCode), formattedDateTime, "Spot " + parkingSpotID
                            );
                        }
                        
                        return "Reservation confirmed for " + reservationDateTime.format(
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + 
                            ". Confirmation code: " + reservationCode;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error making reservation: " + e.getMessage());
            return "Reservation failed: " + e.getMessage();
        }
        return "Reservation failed";
    }

    /**
     * Parse datetime string in various formats
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            // Try "YYYY-MM-DD HH:MM:SS" format first
            if (dateTimeStr.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            // Try "YYYY-MM-DD HH:MM" format
            else if (dateTimeStr.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) {
                return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
            // Try ISO format "YYYY-MM-DDTHH:MM"
            else if (dateTimeStr.contains("T")) {
                return LocalDateTime.parse(dateTimeStr);
            }
            else {
                throw new IllegalArgumentException("Unsupported datetime format: " + dateTimeStr);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid datetime format: " + dateTimeStr + 
                                             ". Use 'YYYY-MM-DD HH:MM' or 'YYYY-MM-DD HH:MM:SS'");
        }
    }

    /**
     * Handles parking entry with subscriber code (immediate parking)
     */
    public String enterParking(String userName) {
        // Get user ID
        int userID = getUserID(userName);
        if (userID == -1) {
            return "Invalid user code";
        }

        // Check if spots are available
        if (getAvailableParkingSpots() <= 0) {
            return "No parking spots available";
        }

        // Find available parking spot
        int spotID = getAvailableParkingSpotID();
        if (spotID == -1) {
            return "No available parking spot found";
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime estimatedEnd = now.plusHours(4); // Default 4 hours

        // Create parking info record for immediate parking
        String qry = """
            INSERT INTO parkinginfo 
            (ParkingSpot_ID, User_ID, Date_Of_Placing_Order, Actual_start_time, 
             Estimated_start_time, Estimated_end_time, IsOrderedEnum, IsLate, IsExtended, statusEnum) 
            VALUES (?, ?, NOW(), ?, ?, ?, 'no', 'no', 'no', 'active')
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(qry, PreparedStatement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, spotID);
            stmt.setInt(2, userID);
            stmt.setTimestamp(3, Timestamp.valueOf(now));
            stmt.setTimestamp(4, Timestamp.valueOf(now));
            stmt.setTimestamp(5, Timestamp.valueOf(estimatedEnd));
            stmt.executeUpdate();

            // Get the generated ParkingInfo_ID (parking code)
            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    int parkingCode = generatedKeys.getInt(1);
                    
                    // Mark parking spot as occupied
                    updateParkingSpotStatus(spotID, true);
                    
                    return "Entry successful. Parking code: " + parkingCode + ". Spot: " + spotID;
                }
            }
        } catch (SQLException e) {
            System.out.println("Error handling entry: " + e.getMessage());
            return "Entry failed";
        }
        return "Entry failed";
    }

    /**
     * Handles parking entry with reservation code - NOW SUPPORTS PREORDER->ACTIVE
     */
    public String enterParkingWithReservation(int reservationCode) {
        // Check if reservation exists and is in preorder status
        String checkQry = """
            SELECT pi.*, u.User_ID 
            FROM parkinginfo pi 
            JOIN users u ON pi.User_ID = u.User_ID 
            WHERE pi.ParkingInfo_ID = ? AND pi.statusEnum = 'preorder'
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(checkQry)) {
            stmt.setInt(1, reservationCode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    LocalDateTime estimatedStartTime = rs.getTimestamp("Estimated_start_time").toLocalDateTime();
                    int userID = rs.getInt("User_ID");
                    int parkingSpotID = rs.getInt("ParkingSpot_ID");
                    
                    // Check if reservation is for today
                    LocalDateTime now = LocalDateTime.now();
                    if (!estimatedStartTime.toLocalDate().equals(now.toLocalDate())) {
                        if (estimatedStartTime.isBefore(now)) {
                            // Cancel expired reservation
                            cancelReservation(reservationCode);
                            return "Reservation expired";
                        } else {
                            return "Reservation is for future date";
                        }
                    }

                    // Update reservation to active status and set actual start time
                    String updateQry = """
                        UPDATE parkinginfo 
                        SET statusEnum = 'active', Actual_start_time = ? 
                        WHERE ParkingInfo_ID = ?
                        """;
                    
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateQry)) {
                        updateStmt.setTimestamp(1, Timestamp.valueOf(now));
                        updateStmt.setInt(2, reservationCode);
                        updateStmt.executeUpdate();
                        
                        // Mark parking spot as occupied
                        updateParkingSpotStatus(parkingSpotID, true);
                        
                        System.out.println("Reservation " + reservationCode + " activated (preorder → active)");
                        return "Entry successful! Reservation activated. Parking code: " + reservationCode + ". Spot: " + parkingSpotID;
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Error handling reservation entry: " + e.getMessage());
        }
        return "Invalid reservation code or reservation not in preorder status";
    }
    
    /**
     * ATTENDANT-ONLY: Register new subscriber (PDF requirement)
     * Only attendants can register new users
     */
    public String registerNewSubscriber(String attendantUserName, String name, String phone, 
                                       String email, String carNumber, String userName) {
        // Verify caller is attendant
        if (!hasRole(attendantUserName, UserRole.ATTENDANT)) {
            return "ERROR: Only parking attendants can register new subscribers";
        }
        
        // Continue with existing registration logic
        return registerNewSubscriberInternal(name, phone, email, carNumber, userName);
    }
    
    /**
     * For backwards compatibility - allow registration without attendant check
     * This can be used by system initialization or admin functions
     */
    public String registerNewSubscriber(String name, String phone, String email, 
                                       String carNumber, String userName) {
        return registerNewSubscriberInternal(name, phone, email, carNumber, userName);
    }
    
    /**
     * Registers a new subscriber in the system - WITH EMAIL NOTIFICATIONS
     */
    private String registerNewSubscriberInternal(String name, String phone, String email, String carNumber, String userName) {
        // Validate input
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
        
        // Check if username already exists
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
        
        // Insert new subscriber
        String insertQry = "INSERT INTO users (UserName, Name, Phone, Email, CarNum, UserTypeEnum) VALUES (?, ?, ?, ?, ?, 'sub')";
        
        try (PreparedStatement stmt = conn.prepareStatement(insertQry, PreparedStatement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, userName);
            stmt.setString(2, name);
            stmt.setString(3, phone);
            stmt.setString(4, email);
            stmt.setString(5, carNumber);
            
            int rowsInserted = stmt.executeUpdate();
            if (rowsInserted > 0) {
                // Get the generated User_ID
                int userID = -1;
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        userID = generatedKeys.getInt(1);
                    }
                }
                
                System.out.println("New subscriber registered: " + userName + " with User_ID: " + userID);
                
                // SEND EMAIL NOTIFICATIONS with User_ID
                EmailService.sendRegistrationConfirmation(email, name, userName, userID);
                EmailService.sendWelcomeMessage(email, name, userName, userID);
                
                return "SUCCESS:Subscriber registered successfully. Username: " + userName + ", User ID: " + userID;
            }
        } catch (SQLException e) {
            System.out.println("Registration failed: " + e.getMessage());
            return "Registration failed: " + e.getMessage();
        }
        
        return "Registration failed: Unknown error";
    }

    /**
     * Generates a unique subscriber code/username
     */
    public String generateUniqueUsername(String baseName) {
        // Remove spaces and special characters
        String cleanName = baseName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        
        // Try the clean name first
        if (isUsernameAvailable(cleanName)) {
            return cleanName;
        }
        
        // If taken, try with numbers
        for (int i = 1; i <= 999; i++) {
            String candidate = cleanName + i;
            if (isUsernameAvailable(candidate)) {
                return candidate;
            }
        }
        
        // Fallback to random number
        return cleanName + System.currentTimeMillis() % 10000;
    }

    /**
     * Handles parking exit - NOW SUPPORTS FINISHING RESERVATIONS
     */
    public String exitParking(String parkingCodeStr) {
        try {
            int parkingCode = Integer.parseInt(parkingCodeStr);
            String qry = """
                SELECT pi.*, ps.ParkingSpot_ID 
                FROM parkinginfo pi 
                JOIN parkingspot ps ON pi.ParkingSpot_ID = ps.ParkingSpot_ID 
                WHERE pi.ParkingInfo_ID = ? AND pi.statusEnum = 'active'
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(qry)) {
                stmt.setInt(1, parkingCode);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int parkingInfoID = rs.getInt("ParkingInfo_ID");
                        int spotID = rs.getInt("ParkingSpot_ID");
                        Timestamp estimatedEndTime = rs.getTimestamp("Estimated_end_time");
                        int userID = rs.getInt("User_ID");
                        String orderType = rs.getString("IsOrderedEnum");
                        
                        LocalDateTime now = LocalDateTime.now();
                        LocalDateTime estimatedEnd = estimatedEndTime.toLocalDateTime();
                        
                        // Check if parking exceeded estimated time
                        boolean isLate = now.isAfter(estimatedEnd);
                        
                        // Update parking info with exit time and finish status
                        String updateQry = """
                            UPDATE parkinginfo 
                            SET Actual_end_time = ?, IsLate = ?, statusEnum = 'finished' 
                            WHERE ParkingInfo_ID = ?
                            """;
                        
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateQry)) {
                            updateStmt.setTimestamp(1, Timestamp.valueOf(now));
                            updateStmt.setString(2, isLate ? "yes" : "no");
                            updateStmt.setInt(3, parkingInfoID);
                            updateStmt.executeUpdate();
                            
                            // Free the parking spot
                            updateParkingSpotStatus(spotID, false);
                            
                            if (isLate) {
                                sendLateExitNotification(userID);
                                return "Exit successful. You were late - please arrive on time for future reservations";
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

    /**
     * Extends parking time
     */
    public String extendParkingTime(String parkingCodeStr, int additionalHours) {
        if (additionalHours < 1 || additionalHours > 4) {
            return "Can only extend parking by 1-4 hours";
        }
        
        try {
            int parkingCode = Integer.parseInt(parkingCodeStr);
            
            // Get user info for email notification
            String getUserQry = """
                SELECT pi.*, u.Email, u.Name 
                FROM parkinginfo pi 
                JOIN users u ON pi.User_ID = u.User_ID 
                WHERE pi.ParkingInfo_ID = ? AND pi.statusEnum = 'active'
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(getUserQry)) {
                stmt.setInt(1, parkingCode);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        Timestamp currentEstimatedEnd = rs.getTimestamp("Estimated_end_time");
                        String userEmail = rs.getString("Email");
                        String userName = rs.getString("Name");
                        
                        LocalDateTime newEstimatedEnd = currentEstimatedEnd.toLocalDateTime().plusHours(additionalHours);
                        
                        String updateQry = """
                            UPDATE parkinginfo 
                            SET Estimated_end_time = ?, IsExtended = 'yes' 
                            WHERE ParkingInfo_ID = ?
                            """;
                        
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateQry)) {
                            updateStmt.setTimestamp(1, Timestamp.valueOf(newEstimatedEnd));
                            updateStmt.setInt(2, parkingCode);
                            updateStmt.executeUpdate();
                            
                            // SEND EMAIL NOTIFICATION
                            if (userEmail != null && userName != null) {
                                EmailService.sendExtensionConfirmation(
                                    userEmail, userName, parkingCodeStr, 
                                    additionalHours, newEstimatedEnd.toString()
                                );
                            }
                            
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

    /**
     * Sends lost parking code to user
     */
    public String sendLostParkingCode(String userName) {
        String qry = """
            SELECT pi.ParkingInfo_ID, u.Email, u.Phone, u.Name 
            FROM parkinginfo pi 
            JOIN users u ON pi.User_ID = u.User_ID 
            WHERE u.UserName = ? AND pi.statusEnum = 'active'
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int parkingCode = rs.getInt("ParkingInfo_ID");
                    String email = rs.getString("Email");
                    String phone = rs.getString("Phone");
                    String name = rs.getString("Name");
                    
                    // SEND EMAIL NOTIFICATION
                    EmailService.sendParkingCodeRecovery(email, name, String.valueOf(parkingCode));
                    
                    return String.valueOf(parkingCode);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error sending lost code: " + e.getMessage());
        }
        return "No active parking session found";
    }

    /**
     * Gets parking history for a user
     */
    public ArrayList<ParkingOrder> getParkingHistory(String userName) {
        ArrayList<ParkingOrder> history = new ArrayList<>();
        String qry = """
            SELECT pi.*, ps.ParkingSpot_ID 
            FROM parkinginfo pi 
            JOIN users u ON pi.User_ID = u.User_ID 
            JOIN parkingspot ps ON pi.ParkingSpot_ID = ps.ParkingSpot_ID 
            WHERE u.UserName = ? 
            ORDER BY pi.Date_Of_Placing_Order DESC
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ParkingOrder order = new ParkingOrder();
                    order.setOrderID(rs.getInt("ParkingInfo_ID"));
                    order.setParkingCode(String.valueOf(rs.getInt("ParkingInfo_ID")));
                    order.setOrderType(rs.getString("IsOrderedEnum"));
                    order.setSpotNumber("Spot " + rs.getInt("ParkingSpot_ID"));
                    
                    // Convert Timestamps to LocalDateTime
                    Timestamp actualStart = rs.getTimestamp("Actual_start_time");
                    Timestamp actualEnd = rs.getTimestamp("Actual_end_time");
                    Timestamp estimatedEnd = rs.getTimestamp("Estimated_end_time");
                    
                    if (actualStart != null) {
                        order.setEntryTime(actualStart.toLocalDateTime());
                    }
                    if (actualEnd != null) {
                        order.setExitTime(actualEnd.toLocalDateTime());
                    }
                    if (estimatedEnd != null) {
                        order.setExpectedExitTime(estimatedEnd.toLocalDateTime());
                    }
                    
                    order.setLate("yes".equals(rs.getString("IsLate")));
                    order.setExtended("yes".equals(rs.getString("IsExtended")));
                    order.setStatus(rs.getString("statusEnum"));
                    
                    history.add(order);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting parking history: " + e.getMessage());
        }
        return history;
    }

    /**
     * Gets all active parking sessions (for attendant view)
     */
    public ArrayList<ParkingOrder> getActiveParkings() {
        ArrayList<ParkingOrder> activeParkings = new ArrayList<>();
        String qry = """
            SELECT pi.*, u.Name, ps.ParkingSpot_ID 
            FROM parkinginfo pi 
            JOIN users u ON pi.User_ID = u.User_ID 
            JOIN parkingspot ps ON pi.ParkingSpot_ID = ps.ParkingSpot_ID 
            WHERE pi.statusEnum = 'active' 
            ORDER BY pi.Actual_start_time
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ParkingOrder order = new ParkingOrder();
                    order.setOrderID(rs.getInt("ParkingInfo_ID"));
                    order.setParkingCode(String.valueOf(rs.getInt("ParkingInfo_ID")));
                    order.setOrderType(rs.getString("IsOrderedEnum"));
                    order.setSubscriberName(rs.getString("Name"));
                    order.setSpotNumber("Spot " + rs.getInt("ParkingSpot_ID"));
                    
                    // Convert Timestamps to LocalDateTime
                    Timestamp actualStart = rs.getTimestamp("Actual_start_time");
                    Timestamp estimatedEnd = rs.getTimestamp("Estimated_end_time");
                    
                    if (actualStart != null) {
                        order.setEntryTime(actualStart.toLocalDateTime());
                    }
                    if (estimatedEnd != null) {
                        order.setExpectedExitTime(estimatedEnd.toLocalDateTime());
                    }
                    
                    order.setStatus("active");
                    activeParkings.add(order);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting active parkings: " + e.getMessage());
        }
        return activeParkings;
    }

    /**
     * Updates subscriber information
     */
    public String updateSubscriberInfo(String updateData) {
        // Format: userName,phone,email
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

    /**
     * Cancels a reservation
     */
    public String cancelReservation(int reservationCode) {
        // Get user info before cancelling for email notification
        String getUserQry = """
            SELECT u.Email, u.Name 
            FROM parkinginfo pi 
            JOIN users u ON pi.User_ID = u.User_ID 
            WHERE pi.ParkingInfo_ID = ?
            """;
        String userEmail = null;
        String userName = null;
        
        try (PreparedStatement stmt = conn.prepareStatement(getUserQry)) {
            stmt.setInt(1, reservationCode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    userEmail = rs.getString("Email");
                    userName = rs.getString("Name");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting user info for cancellation: " + e.getMessage());
        }
        
        String qry = """
            UPDATE parkinginfo 
            SET statusEnum = 'cancelled' 
            WHERE ParkingInfo_ID = ? AND statusEnum IN ('preorder', 'active')
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setInt(1, reservationCode);
            int rowsUpdated = stmt.executeUpdate();
            
            if (rowsUpdated > 0) {
                // Also free up the spot if it was assigned
                freeSpotForReservation(reservationCode);
                
                // SEND EMAIL NOTIFICATION
                if (userEmail != null && userName != null) {
                    EmailService.sendReservationCancelled(userEmail, userName, String.valueOf(reservationCode));
                }
                
                return "Reservation cancelled successfully";
            }
        } catch (SQLException e) {
            System.out.println("Error cancelling reservation: " + e.getMessage());
        }
        return "Reservation not found or already cancelled/finished";
    }

    /**
     * Logs out a user (for future use if needed)
     */
    public void logoutUser(String userName) {
        System.out.println("User logged out: " + userName);
    }

    /**
     * Initializes parking spots if they don't exist
     */
    public void initializeParkingSpots() {
        try {
            // Check if spots already exist
            String checkQry = "SELECT COUNT(*) FROM ParkingSpot";
            try (PreparedStatement stmt = conn.prepareStatement(checkQry)) {
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        // Initialize parking spots - AUTO_INCREMENT will handle ParkingSpot_ID
                        String insertQry = "INSERT INTO ParkingSpot (isOccupied) VALUES (false)";
                        try (PreparedStatement insertStmt = conn.prepareStatement(insertQry)) {
                            for (int i = 1; i <= TOTAL_PARKING_SPOTS; i++) {
                                insertStmt.executeUpdate();
                            }
                        }
                        System.out.println("Successfully initialized " + TOTAL_PARKING_SPOTS + " parking spots with AUTO_INCREMENT");
                    } else {
                        System.out.println("Parking spots already exist: " + rs.getInt(1) + " spots found");
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Error initializing parking spots: " + e.getMessage());
        }
    }

    // ========== HELPER METHODS ==========
    
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

    /**
     * Send late exit notification
     */
    private void sendLateExitNotification(int userID) {
        String qry = "SELECT Email, Phone, Name FROM users WHERE User_ID = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setInt(1, userID);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String email = rs.getString("Email");
                    String phone = rs.getString("Phone");
                    String name = rs.getString("Name");
                    
                    // SEND EMAIL NOTIFICATION
                    EmailService.sendLatePickupNotification(email, name);
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
    
    private void freeSpotForReservation(int reservationCode) {
        String query = """
            UPDATE parkingspot ps
            JOIN parkinginfo pi ON ps.ParkingSpot_ID = pi.ParkingSpot_ID
            SET ps.isOccupied = FALSE
            WHERE pi.ParkingInfo_ID = ?
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setInt(1, reservationCode);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Error freeing spot for reservation: " + e.getMessage());
        }
    }

    /**
     * Activate reservation when customer arrives (PREORDER → ACTIVE)
     */
    public String activateReservation(String subscriberUserName, int reservationCode) {
        // Check if reservation exists and is in preorder status
        String checkQry = """
            SELECT pi.*, u.UserName,
                   TIMESTAMPDIFF(MINUTE, pi.Estimated_start_time, NOW()) as minutes_since_start
            FROM parkinginfo pi 
            JOIN users u ON pi.User_ID = u.User_ID 
            WHERE pi.ParkingInfo_ID = ? AND pi.statusEnum = 'preorder'
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(checkQry)) {
            stmt.setInt(1, reservationCode);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int minutesSinceStart = rs.getInt("minutes_since_start");
                    int spotId = rs.getInt("ParkingSpot_ID");
                    
                    // Check if within 15-minute grace period
                    if (minutesSinceStart > 15) {
                        // Too late - auto-cancel
                        cancelReservation(subscriberUserName, reservationCode);
                        return "Reservation cancelled due to late arrival (over 15 minutes). Please make a new reservation.";
                    }
                    
                    // Update reservation status to ACTIVE and set actual start time
                    LocalDateTime now = LocalDateTime.now();
                    String updateQry = """
                        UPDATE parkinginfo 
                        SET statusEnum = 'active', 
                            Actual_start_time = ?, 
                            IsLate = ?
                        WHERE ParkingInfo_ID = ?
                        """;
                    
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateQry)) {
                        updateStmt.setTimestamp(1, Timestamp.valueOf(now));
                        updateStmt.setString(2, minutesSinceStart > 0 ? "yes" : "no");
                        updateStmt.setInt(3, reservationCode);
                        updateStmt.executeUpdate();
                        
                        // Mark parking spot as occupied
                        updateParkingSpotStatus(spotId, true);
                        
                        String lateMessage = minutesSinceStart > 0 ? 
                            " (Note: " + minutesSinceStart + " minutes late)" : "";
                        
                        System.out.println("Reservation " + reservationCode + " activated (preorder → active)" + lateMessage);
                        
                        return "Reservation activated! Parking code: " + reservationCode + 
                               ". Spot: " + spotId + lateMessage;
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Error activating reservation: " + e.getMessage());
            return "Failed to activate reservation";
        }
        
        return "Reservation not found or already activated";
    }

    /**
     * Cancel reservation
     */
    public String cancelReservation(String subscriberUserName, int reservationCode) {
        return cancelReservationInternal(reservationCode, "User requested cancellation");
    }

    /**
     * Internal cancellation method (used by auto-cancel and manual cancel)
     */
    private String cancelReservationInternal(int reservationCode, String reason) {
        // Get reservation info first for email notification
        String getUserQry = """
            SELECT u.Email, u.Name, pi.statusEnum, pi.ParkingSpot_ID
            FROM parkinginfo pi 
            JOIN users u ON pi.User_ID = u.User_ID 
            WHERE pi.ParkingInfo_ID = ?
            """;
        
        String userEmail = null;
        String userName = null;
        String currentStatus = null;
        Integer spotId = null;
        
        try (PreparedStatement stmt = conn.prepareStatement(getUserQry)) {
            stmt.setInt(1, reservationCode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    userEmail = rs.getString("Email");
                    userName = rs.getString("Name");
                    currentStatus = rs.getString("statusEnum");
                    spotId = rs.getObject("ParkingSpot_ID", Integer.class);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting reservation info for cancellation: " + e.getMessage());
        }
        
        // Update reservation status to cancelled
        String qry = """
            UPDATE parkinginfo 
            SET statusEnum = 'cancelled' 
            WHERE ParkingInfo_ID = ? AND statusEnum IN ('preorder', 'active')
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setInt(1, reservationCode);
            int rowsUpdated = stmt.executeUpdate();
            
            if (rowsUpdated > 0) {
                // Free up the spot if it was assigned
                if (spotId != null) {
                    updateParkingSpotStatus(spotId, false);
                }
                
                // Send email notification
                if (userEmail != null && userName != null) {
                    EmailService.sendReservationCancelled(userEmail, userName, String.valueOf(reservationCode));
                }
                
                System.out.println("Reservation " + reservationCode + " cancelled (" + currentStatus + " → cancelled) - " + reason);
                return "Reservation cancelled successfully";
            }
        } catch (SQLException e) {
            System.out.println("Error cancelling reservation: " + e.getMessage());
        }
        
        return "Reservation not found or already cancelled/finished";
    }
}