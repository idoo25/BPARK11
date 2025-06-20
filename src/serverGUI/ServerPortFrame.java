package serverGUI;

import java.util.Collection;
import java.util.Map;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import ocsf.server.ConnectionToClient;
import server.ParkingServer;
import controllers.ParkingController;
import controllers.ReportController;
import server.ServerUI;

/**
 * ServerPortFrame provides the GUI interface for managing the ParkB server.
 * Now includes enhanced monitoring service status display.
 */
public class ServerPortFrame extends Application {
    public static String str = "";

    @FXML
    private Button btnExit = null;
    @FXML
    private TextField textMessage;
    @FXML
    private TextField serverip;
    @FXML
    private TextArea txtClientConnection;

    ServerPortFrame controller;

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/serverGUI/ServerGUI.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root);
        controller = loader.getController();
        ParkingServer.spf = this;
        primaryStage.setTitle("ParkB Server Management");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        // Auto-start server when GUI launches
        autoStartServer();
    }

    /**
     * Automatically starts the server with preset database configuration
     */
    private void autoStartServer() {
        Platform.runLater(() -> {
            try {
                // Auto-configure database connection with preset credentials
                String dbName = "bpark";
                String dbPassword = "Aa123456";
                
                controller.textMessage.setText("Auto-starting ParkB Server...");
                
                // Initialize controllers with auto-configured credentials
                ParkingServer.parkingController = new ParkingController(dbName, dbPassword);
                ParkingServer.reportController = new ReportController(dbName, dbPassword);
                
                if (ParkingServer.parkingController.successFlag == 1) {
                    // Start the server
                    ServerUI.runServer(ParkingServer.DEFAULT_PORT.toString());
                    controller.serverip.setText(ParkingServer.serverIp);
                    controller.textMessage.setText("ParkB Server Running Successfully!");
                    
                    // Show connection info with enhanced monitoring status
                    showSystemInfo();
                } else {
                    controller.textMessage.setText("Database connection failed! Check MySQL server.");
                }
            } catch (Exception e) {
                controller.textMessage.setText("Error starting server: " + e.getMessage());
            }
        });
    }

    /**
     * Handles the Exit button click event.
     */
    @FXML
    public void getExitBtn(ActionEvent event) throws Exception {
        System.out.println("Shutting down ParkB Server");
        
        // Shutdown server gracefully including enhanced monitoring service
        if (ParkingServer.parkingController != null) {
            ParkingServer.parkingController.shutdown();
            System.out.println("Auto-monitoring service stopped during shutdown");
        }
        
        System.exit(0);
    }

    /**
     * Updates the client connections display in the GUI.
     * @param clientsMap Map containing client connection information
     */
    public void printConnection(Map<ConnectionToClient, String> clientsMap) {
        System.out.println("Client connections: " + clientsMap);
        Platform.runLater(() -> {
            String toPrint = "";
            Collection<String> values = clientsMap.values();
            for (String value : values) {
                toPrint = toPrint + value + "\n";
            }
            if (controller != null && controller.txtClientConnection != null) {
                String currentText = controller.txtClientConnection.getText();
                if (currentText.contains("=== ParkB Server")) {
                    // Keep the system info and append connections
                    controller.txtClientConnection.setText(currentText.split("Waiting for clients")[0] + 
                        "Client Connections:\n" + toPrint);
                } else {
                    controller.txtClientConnection.setText(toPrint);
                }
            }
        });
    }

    /**
     * Shows system information when server starts successfully
     * UPDATED: Now includes enhanced monitoring service status
     */
    private void showSystemInfo() {
        Platform.runLater(() -> {
            String systemInfo = "=== ParkB Server Auto-Started ===\n";
            systemInfo += "Database: bpark (Auto-configured)\n";
            systemInfo += "MySQL: localhost:3306 (Connected)\n";
            systemInfo += "Username: root\n";
            systemInfo += "Server IP: " + ParkingServer.serverIp + "\n";
            systemInfo += "Port: " + ParkingServer.DEFAULT_PORT + "\n";
            systemInfo += "Parking Spots: 100 (Auto-initialized)\n";
            
            // Add enhanced monitoring status
            systemInfo += "\n=== AUTO-MONITORING SERVICE ACTIVE ===\n";
            systemInfo += "✅ Pre-order Cancellation: 15-min rule\n";
            systemInfo += "✅ Late Pickup Detection: 15-min rule\n";
            systemInfo += "• Checking every minute for violations\n";
            systemInfo += "• Auto-cancel late arrivals (preorder)\n";
            systemInfo += "• Auto-notify late pickups (active)\n";
            systemInfo += "• Email notifications sent automatically\n";
            
            systemInfo += "\nAuto-start: SUCCESS\n";
            systemInfo += "Status: Ready to accept client connections\n";
            systemInfo += "================================\n\n";
            systemInfo += "Monitor console for automated actions:\n";
            systemInfo += "✅ AUTO-CANCELLED: Reservation X for UserY\n";
            systemInfo += "⏰ LATE PICKUP: Parking X for UserY\n\n";
            systemInfo += "Waiting for clients to connect...\n";
            
            if (controller != null && controller.txtClientConnection != null) {
                controller.txtClientConnection.setText(systemInfo);
            }
        });
    }
}