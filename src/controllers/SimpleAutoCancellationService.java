package controllers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import services.EmailService;

/**
 * Enhanced Automatic Service for:
 * 1. Reservation Cancellation (15-minute rule for preorders)
 * 2. Late Pickup Monitoring (15-minute rule for active parkings)
 * 
 * Runs every minute to check both conditions and send email notifications
 */
public class SimpleAutoCancellationService {
    
    private final ParkingController parkingController;
    private final ScheduledExecutorService scheduler;
    private static final int LATE_THRESHOLD_MINUTES = 15;
    private boolean isRunning = false;
    
    public SimpleAutoCancellationService(ParkingController parkingController) {
        this.parkingController = parkingController;
        this.scheduler = Executors.newScheduledThreadPool(1);
    }
    
    /**
     * Start the automatic monitoring service
     * Runs every minute to check for:
     * 1. Late preorder reservations (auto-cancel)
     * 2. Late active parkings (mark as late and notify)
     */
    public void startService() {
        if (isRunning) {
            System.out.println("Auto-monitoring service is already running");
            return;
        }
        
        isRunning = true;
        System.out.println("Starting automatic monitoring service...");
        System.out.println("Checking every minute for:");
        System.out.println("  - Late preorder reservations (15+ min late = auto-cancel)");
        System.out.println("  - Late active parkings (15+ min late = notify customer)");
        
        // Schedule to run every minute
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAndCancelLatePreorders();
                checkAndNotifyLatePickups();
            } catch (Exception e) {
                System.err.println("Error in auto-monitoring service: " + e.getMessage());
            }
        }, 0, 1, TimeUnit.MINUTES);
    }
    
    /**
     * Stop the automatic monitoring service
     */
    public void stopService() {
        if (!isRunning) {
            return;
        }
        
        isRunning = false;
        scheduler.shutdown();
        System.out.println("Auto-monitoring service stopped");
    }
    
    /**
     * Check for and cancel late preorder reservations
     */
    private void checkAndCancelLatePreorders() {
        String query = """
            SELECT 
                pi.ParkingInfo_ID,
                pi.User_ID,
                pi.ParkingSpot_ID,
                u.UserName,
                u.Email,
                u.Name,
                u.Phone,
                TIMESTAMPDIFF(MINUTE, pi.Estimated_start_time, NOW()) as minutes_late,
                pi.Estimated_start_time
            FROM parkinginfo pi
            JOIN users u ON pi.User_ID = u.User_ID
            WHERE pi.statusEnum = 'preorder'
            AND DATE(pi.Estimated_start_time) = CURDATE()
            AND pi.ParkingSpot_ID IS NOT NULL
            AND pi.Estimated_start_time IS NOT NULL
            AND TIMESTAMPDIFF(MINUTE, pi.Estimated_start_time, NOW()) >= ?
            """;
        
        try (PreparedStatement stmt = parkingController.getConnection().prepareStatement(query)) {
            stmt.setInt(1, LATE_THRESHOLD_MINUTES);
            
            try (ResultSet rs = stmt.executeQuery()) {
                int cancelledCount = 0;
                
                while (rs.next()) {
                    int reservationCode = rs.getInt("ParkingInfo_ID");
                    int spotId = rs.getInt("ParkingSpot_ID");
                    String userName = rs.getString("UserName");
                    String userEmail = rs.getString("Email");
                    String fullName = rs.getString("Name");
                    int minutesLate = rs.getInt("minutes_late");
                    
                    if (cancelLateReservation(reservationCode, spotId)) {
                        cancelledCount++;
                        
                        // Send email notification for auto-cancellation
                        if (userEmail != null && fullName != null) {
                            EmailService.sendReservationCancelled(userEmail, fullName, String.valueOf(reservationCode));
                        }
                        
                        System.out.println(String.format(
                            "✅ AUTO-CANCELLED: Reservation %d for %s (Spot %d) - %d minutes late - Email sent",
                            reservationCode, userName, spotId, minutesLate
                        ));
                    }
                }
                
                if (cancelledCount > 0) {
                    System.out.println(String.format(
                        "[%s] Auto-cancellation: %d preorder reservations cancelled",
                        getCurrentTimestamp(), cancelledCount
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error during auto-cancellation: " + e.getMessage());
        }
    }
    
    /**
     * NEW METHOD: Check for late pickups in active parkings and send notifications
     */
    private void checkAndNotifyLatePickups() {
        String query = """
            SELECT 
                pi.ParkingInfo_ID,
                pi.User_ID,
                pi.ParkingSpot_ID,
                u.UserName,
                u.Email,
                u.Name,
                u.Phone,
                TIMESTAMPDIFF(MINUTE, pi.Estimated_end_time, NOW()) as minutes_late,
                pi.Estimated_end_time,
                pi.IsLate
            FROM parkinginfo pi
            JOIN users u ON pi.User_ID = u.User_ID
            WHERE pi.statusEnum = 'active'
            AND pi.Actual_end_time IS NULL
            AND pi.Estimated_end_time IS NOT NULL
            AND TIMESTAMPDIFF(MINUTE, pi.Estimated_end_time, NOW()) >= ?
            AND pi.IsLate = 'no'
            """;
        
        try (PreparedStatement stmt = parkingController.getConnection().prepareStatement(query)) {
            stmt.setInt(1, LATE_THRESHOLD_MINUTES);
            
            try (ResultSet rs = stmt.executeQuery()) {
                int notifiedCount = 0;
                
                while (rs.next()) {
                    int parkingInfoId = rs.getInt("ParkingInfo_ID");
                    String userName = rs.getString("UserName");
                    String userEmail = rs.getString("Email");
                    String fullName = rs.getString("Name");
                    int spotId = rs.getInt("ParkingSpot_ID");
                    int minutesLate = rs.getInt("minutes_late");
                    
                    if (markAsLateAndNotify(parkingInfoId, userEmail, fullName)) {
                        notifiedCount++;
                        
                        System.out.println(String.format(
                            "⏰ LATE PICKUP: Parking %d for %s (Spot %d) - %d minutes late - Email sent",
                            parkingInfoId, userName, spotId, minutesLate
                        ));
                    }
                }
                
                if (notifiedCount > 0) {
                    System.out.println(String.format(
                        "[%s] Late pickup monitoring: %d customers notified",
                        getCurrentTimestamp(), notifiedCount
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error during late pickup check: " + e.getMessage());
        }
    }
    
    /**
     * Mark parking as late and send email notification
     */
    private boolean markAsLateAndNotify(int parkingInfoId, String userEmail, String fullName) {
        Connection conn = parkingController.getConnection();
        
        try {
            // Update IsLate to 'yes'
            String updateQuery = """
                UPDATE parkinginfo 
                SET IsLate = 'yes'
                WHERE ParkingInfo_ID = ? AND statusEnum = 'active' AND IsLate = 'no'
                """;
            
            int updated = 0;
            try (PreparedStatement stmt = conn.prepareStatement(updateQuery)) {
                stmt.setInt(1, parkingInfoId);
                updated = stmt.executeUpdate();
            }
            
            if (updated > 0) {
                // Send late pickup email notification
                if (userEmail != null && fullName != null) {
                    EmailService.sendLatePickupNotification(userEmail, fullName);
                }
                return true;
            }
            return false;
            
        } catch (SQLException e) {
            System.err.println("Error marking parking as late: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Cancel a specific late preorder reservation
     * NOTE: Preorder reservations don't set isOccupied=true, so no need to update parkingspot table
     */
    private boolean cancelLateReservation(int reservationCode, int spotId) {
        Connection conn = parkingController.getConnection();
        
        try {
            // Cancel the reservation (change status from preorder to cancelled)
            String cancelQuery = """
                UPDATE parkinginfo 
                SET statusEnum = 'cancelled'
                WHERE ParkingInfo_ID = ? AND statusEnum = 'preorder'
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(cancelQuery)) {
                stmt.setInt(1, reservationCode);
                int updatedReservations = stmt.executeUpdate();
                
                if (updatedReservations > 0) {
                    // Note: We don't need to update parkingspot.isOccupied because
                    // preorder reservations don't mark the spot as occupied
                    // Only active parkings set isOccupied=true
                    System.out.println("Preorder reservation " + reservationCode + 
                                     " cancelled. Spot " + spotId + " remains available for others.");
                    return true;
                }
            }
            
        } catch (SQLException e) {
            System.err.println("Failed to cancel reservation " + reservationCode + ": " + e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Check if a reservation should be changed from preorder to active when customer arrives
     */
    public boolean activateReservation(int reservationCode) {
        String query = """
            UPDATE parkinginfo 
            SET statusEnum = 'active', Actual_start_time = NOW()
            WHERE ParkingInfo_ID = ? AND statusEnum = 'preorder'
            """;
        
        try (PreparedStatement stmt = parkingController.getConnection().prepareStatement(query)) {
            stmt.setInt(1, reservationCode);
            int updated = stmt.executeUpdate();
            
            if (updated > 0) {
                System.out.println("Reservation " + reservationCode + " activated (preorder → active)");
                return true;
            }
            return false;
            
        } catch (SQLException e) {
            System.err.println("Error activating reservation: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Finish a reservation (change from active to finished when customer exits)
     */
    public boolean finishReservation(int reservationCode, int spotId) {
        Connection conn = parkingController.getConnection();
        
        try {
            conn.setAutoCommit(false);
            
            // 1. Update reservation status to finished and set actual end time
            String finishQuery = """
                UPDATE parkinginfo 
                SET statusEnum = 'finished', Actual_end_time = NOW()
                WHERE ParkingInfo_ID = ? AND statusEnum = 'active'
                """;
            
            int updated = 0;
            try (PreparedStatement stmt = conn.prepareStatement(finishQuery)) {
                stmt.setInt(1, reservationCode);
                updated = stmt.executeUpdate();
            }
            
            if (updated == 0) {
                conn.rollback();
                return false;
            }
            
            // 2. Free up the parking spot
            String freeSpotQuery = """
                UPDATE parkingspot 
                SET isOccupied = FALSE 
                WHERE ParkingSpot_ID = ?
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(freeSpotQuery)) {
                stmt.setInt(1, spotId);
                stmt.executeUpdate();
            }
            
            conn.commit();
            System.out.println("Reservation " + reservationCode + " finished and spot " + spotId + " freed");
            return true;
            
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                System.err.println("Failed to rollback: " + rollbackEx.getMessage());
            }
            System.err.println("Error finishing reservation: " + e.getMessage());
            return false;
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException e) {
                System.err.println("Failed to reset auto-commit: " + e.getMessage());
            }
        }
    }
    
    /**
     * Get current timestamp for logging
     */
    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    
    /**
     * Check if service is running
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * Shutdown the service
     */
    public void shutdown() {
        stopService();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}