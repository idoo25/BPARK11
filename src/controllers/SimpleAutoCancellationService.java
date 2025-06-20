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
 * Simplified Automatic Reservation Cancellation Service
 * 15-minute rule: If a customer with "preorder" status is late by more than 15 minutes,
 * their reservation is automatically cancelled and the spot becomes available.
 * Updated to work with unified parkinginfo table structure
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
     * Start the automatic cancellation service
     * Runs every minute to check for late preorder reservations
     */
    public void startService() {
        if (isRunning) {
            System.out.println("Auto-cancellation service is already running");
            return;
        }
        
        isRunning = true;
        System.out.println("Starting automatic reservation cancellation service...");
        System.out.println("Checking for late preorder reservations every minute (15+ min late = auto-cancel)");
        
        // Schedule to run every minute
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAndCancelLatePreorders();
            } catch (Exception e) {
                System.err.println("Error in auto-cancellation service: " + e.getMessage());
            }
        }, 0, 1, TimeUnit.MINUTES);
    }
    
    /**
     * Stop the automatic cancellation service
     */
    public void stopService() {
        if (!isRunning) {
            return;
        }
        
        isRunning = false;
        scheduler.shutdown();
        System.out.println("Auto-cancellation service stopped");
    }
    
    /**
     * Main method that checks for and cancels late preorder reservations
     * NOW WITH EMAIL NOTIFICATIONS
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
                        
                        // SEND EMAIL NOTIFICATION for auto-cancellation
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
                        "Auto-cancellation completed: %d preorder reservations cancelled, %d spots freed, %d emails sent",
                        cancelledCount, cancelledCount, cancelledCount
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error during auto-cancellation: " + e.getMessage());
        }
    }
    
    /**
     * Cancel a specific late preorder reservation and free up the parking spot
     */
    private boolean cancelLateReservation(int reservationCode, int spotId) {
        Connection conn = parkingController.getConnection();
        
        try {
            conn.setAutoCommit(false);
            
            // 1. Cancel the reservation (change status from preorder to cancelled)
            String cancelQuery = """
                UPDATE parkinginfo 
                SET statusEnum = 'cancelled'
                WHERE ParkingInfo_ID = ? AND statusEnum = 'preorder'
                """;
            
            int updatedReservations = 0;
            try (PreparedStatement stmt = conn.prepareStatement(cancelQuery)) {
                stmt.setInt(1, reservationCode);
                updatedReservations = stmt.executeUpdate();
            }
            
            if (updatedReservations == 0) {
                conn.rollback();
                return false; // Reservation was already cancelled or doesn't exist
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
            return true;
            
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                System.err.println("Failed to rollback transaction: " + rollbackEx.getMessage());
            }
            System.err.println("Failed to cancel reservation " + reservationCode + ": " + e.getMessage());
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