package services;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * EmailService for BPark System - Hebrew Only Handles all email notifications
 * for the parking system
 * Updated to include User_ID in registration emails
 */
public class EmailService {
    
    // Email configuration
    private static final String GMAIL_USERNAME = "idopo25@gmail.com";
    private static final String GMAIL_APP_PASSWORD = "kylk wqxz kquw vccf";
    private static final String SUPPORT_PHONE = "1-800-800-123";
    private static final String SUPPORT_EMAIL = "support@bpark.com";
    private static final String COMPANY_NAME = "BPARK";
    private static final String LOGO_URL = "https://i.postimg.cc/7LFkRhp3/Screenshot-2025-06-04-180239.jpg";
    
    // Email notification types
    public enum NotificationType {
        LATE_PICKUP,
        REGISTRATION_CONFIRMATION,
        RESERVATION_CONFIRMATION,
        RESERVATION_CANCELLED,
        PARKING_CODE_RECOVERY,
        EXTENSION_CONFIRMATION,
        PARKING_EXPIRED,
        WELCOME_MESSAGE
    }
    
    /**
     * Main method to send any type of email notification (Hebrew only)
     */
    public static boolean sendNotification(NotificationType type, String recipientEmail, 
                                         String customerName, Object... additionalData) {
        try {
            Session session = createEmailSession();
            MimeMessage message = new MimeMessage(session);
            
            // Set sender
            message.setFrom(new InternetAddress(GMAIL_USERNAME, COMPANY_NAME + " System"));
            message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipientEmail));
            
            // Get email content based on type
            EmailContent content = generateEmailContent(type, customerName, additionalData);
            message.setSubject(content.subject);
            message.setContent(content.htmlBody, "text/html; charset=UTF-8");
            
            Transport.send(message);
            System.out.println("✅ Email sent successfully: " + type + " to " + recipientEmail);
            return true;
            
        } catch (Exception e) {
            System.err.println("❌ Failed to send email: " + type + " to " + recipientEmail);
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Specific methods for easy integration
     */
    public static boolean sendLatePickupNotification(String recipientEmail, String customerName) {
        return sendNotification(NotificationType.LATE_PICKUP, recipientEmail, customerName);
    }
    
    public static boolean sendRegistrationConfirmation(String recipientEmail, String customerName, String username, int userID) {
        return sendNotification(NotificationType.REGISTRATION_CONFIRMATION, recipientEmail, customerName, username, userID);
    }
    
    public static boolean sendReservationConfirmation(String recipientEmail, String customerName, 
                                                    String reservationCode, String date, String spotNumber) {
        return sendNotification(NotificationType.RESERVATION_CONFIRMATION, recipientEmail, customerName, 
                              reservationCode, date, spotNumber);
    }
    
    public static boolean sendReservationCancelled(String recipientEmail, String customerName, String reservationCode) {
        return sendNotification(NotificationType.RESERVATION_CANCELLED, recipientEmail, customerName, reservationCode);
    }
    
    public static boolean sendParkingCodeRecovery(String recipientEmail, String customerName, String parkingCode) {
        return sendNotification(NotificationType.PARKING_CODE_RECOVERY, recipientEmail, customerName, parkingCode);
    }
    
    public static boolean sendExtensionConfirmation(String recipientEmail, String customerName, 
                                                  String parkingCode, int hours, String newEndTime) {
        return sendNotification(NotificationType.EXTENSION_CONFIRMATION, recipientEmail, customerName, 
                              parkingCode, hours, newEndTime);
    }
    
    public static boolean sendParkingExpiredNotification(String recipientEmail, String customerName, String spotNumber) {
        return sendNotification(NotificationType.PARKING_EXPIRED, recipientEmail, customerName, spotNumber);
    }
    
    public static boolean sendWelcomeMessage(String recipientEmail, String customerName, String username, int userID) {
        return sendNotification(NotificationType.WELCOME_MESSAGE, recipientEmail, customerName, username, userID);
    }
    
    /**
     * Create email session with Gmail SMTP configuration
     */
    private static Session createEmailSession() {
        Properties properties = new Properties();
        properties.put("mail.smtp.host", "smtp.gmail.com");
        properties.put("mail.smtp.port", "587");
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", "true");
        properties.put("mail.smtp.starttls.required", "true");
        properties.put("mail.smtp.ssl.protocols", "TLSv1.2");
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        
        return Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(GMAIL_USERNAME, GMAIL_APP_PASSWORD);
            }
        });
    }
    
    /**
     * Generate email content based on notification type (Hebrew only)
     */
    private static EmailContent generateEmailContent(NotificationType type, String customerName, 
                                                   Object... additionalData) {
        String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        
        switch (type) {
            case LATE_PICKUP:
                return createLatePickupContent(customerName, currentDate, currentTime);
                
            case REGISTRATION_CONFIRMATION:
                String username = (String) additionalData[0];
                Integer userID = (Integer) additionalData[1];
                return createRegistrationContent(customerName, username, userID, currentDate, currentTime);
                
            case RESERVATION_CONFIRMATION:
                String reservationCode = (String) additionalData[0];
                String reservationDate = (String) additionalData[1];
                String spotNumber = (String) additionalData[2];
                return createReservationContent(customerName, reservationCode, reservationDate, spotNumber);
                
            case RESERVATION_CANCELLED:
                String cancelledCode = (String) additionalData[0];
                return createCancellationContent(customerName, cancelledCode, currentDate, currentTime);
                
            case PARKING_CODE_RECOVERY:
                String parkingCode = (String) additionalData[0];
                return createCodeRecoveryContent(customerName, parkingCode, currentDate, currentTime);
                
            case EXTENSION_CONFIRMATION:
                String extendCode = (String) additionalData[0];
                Integer hours = (Integer) additionalData[1];
                String newEndTime = (String) additionalData[2];
                return createExtensionContent(customerName, extendCode, hours, newEndTime);
                
            case PARKING_EXPIRED:
                String expiredSpot = (String) additionalData[0];
                return createExpiredContent(customerName, expiredSpot, currentDate, currentTime);
                
            case WELCOME_MESSAGE:
                String welcomeUsername = (String) additionalData[0];
                Integer welcomeUserID = (Integer) additionalData[1];
                return createWelcomeContent(customerName, welcomeUsername, welcomeUserID);
                
            default:
                return createDefaultContent(customerName);
        }
    }
    
    /**
     * Email content structure
     */
    private static class EmailContent {
        String subject;
        String htmlBody;
        
        EmailContent(String subject, String htmlBody) {
            this.subject = subject;
            this.htmlBody = htmlBody;
        }
    }
    
    /**
     * Create late pickup notification content (your original design)
     */
    private static EmailContent createLatePickupContent(String customerName, String date, String time) {
        String subject = "הודעה על איחור באיסוף הרכב - " + date;
        String content = createEmailTemplate(customerName, date, time,
            "הודעה על איחור באיסוף הרכב",
            (customerName != null && !customerName.trim().isEmpty() ? 
                "לקוח/ה יקר/ה " + customerName + "," : "לקוח/ה יקר/ה,"),
            "ברצוננו להודיעך כי חלה חריגה בזמן איסוף הרכב מהחניון, מעבר לזמן שהוזמן מראש.<br>" +
            "נודה לך אם תוכל/י להגיע לאסוף את רכבך בהקדם.",
            "<strong>לתשומת לבך:</strong> ייתכן שיחולו חיובים נוספים בגין שהות מעבר לזמן שהוזמן.",
            "#fff3cd", "#ffc107"
        );
        return new EmailContent(subject, content);
    }
    
    /**
     * Create registration confirmation content - UPDATED WITH USER_ID
     */
    private static EmailContent createRegistrationContent(String customerName, String username, int userID, String date, String time) {
        String subject = "ברוכים הבאים ל-BPARK - רישום מוצלח!";
        String content = createEmailTemplate(customerName, date, time,
            "ברוכים הבאים ל-BPARK!",
            "שלום " + customerName + " וברוכים הבאים!",
            "ברוכים הבאים למערכת החניון החכם BPARK!<br>" +
            "רישומך הושלם בהצלחה.<br><br>" +
            "<strong>מספר מזהה הלקוח שלך הוא:</strong> " + userID + "<br>" +
            "<strong>שם המשתמש שלך:</strong> " + username + "<br><br>" +
            "כעת תוכל להזמין מקומות חניה, לנהל הזמנות ולקבל עדכונים בזמן אמת.",
            "<strong>טיפ:</strong> שמור את מספר המזהה ושם המשתמש שלך במקום בטוח לכניסה מהירה למערכת.",
            "#d4edda", "#28a745"
        );
        return new EmailContent(subject, content);
    }
    
    /**
     * Create reservation confirmation content
     */
    private static EmailContent createReservationContent(String customerName, String reservationCode, 
                                                       String reservationDate, String spotNumber) {
        String subject = "אישור הזמנת חניה - קוד " + reservationCode;
        String content = createEmailTemplate(customerName, LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), 
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
            "אישור הזמנת חניה",
            "שלום " + customerName + ",",
            "הזמנת החניה שלך אושרה בהצלחה!<br><br>" +
            "<strong>קוד הזמנה:</strong> " + reservationCode + "<br>" +
            "<strong>תאריך:</strong> " + reservationDate + "<br>" +
            "<strong>מקום חניה:</strong> " + spotNumber + "<br><br>" +
            "אנא הגע עם קוד ההזמנה למכונת הכניסה.",
            "<strong>חשוב:</strong> הגעה מאוחרת מעל 15 דקות עלולה לגרום לביטול אוטומטי של ההזמנה.",
            "#d1ecf1", "#17a2b8"
        );
        return new EmailContent(subject, content);
    }
    
    /**
     * Create cancellation notification content
     */
    private static EmailContent createCancellationContent(String customerName, String reservationCode, String date, String time) {
        String subject = "ביטול הזמנת חניה - קוד " + reservationCode;
        String content = createEmailTemplate(customerName, date, time,
            "ביטול הזמנת חניה",
            "שלום " + customerName + ",",
            "הזמנת החניה שלך בוטלה.<br><br>" +
            "<strong>קוד הזמנה מבוטל:</strong> " + reservationCode + "<br><br>" +
            "הביטול יכול להיות מסיבות הבאות:<br>" +
            "• איחור של מעל 15 דקות (ביטול אוטומטי)<br>" +
            "• ביטול ידני על ידך<br>" +
            "• בעיה טכנית במערכת",
            "<strong>הערה:</strong> אם לא ביטלת בעצמך, ניתן ליצור הזמנה חדשה דרך המערכת.",
            "#f8d7da", "#dc3545"
        );
        return new EmailContent(subject, content);
    }
    
    /**
     * Create parking code recovery content
     */
    private static EmailContent createCodeRecoveryContent(String customerName, String parkingCode, String date, String time) {
        String subject = "שחזור קוד חניה - BPARK";
        String content = createEmailTemplate(customerName, date, time,
            "שחזור קוד חניה",
            "שלום " + customerName + ",",
            "לפי בקשתך, להלן קוד החניה הפעיל שלך:<br><br>" +
            "<div style='background:#e2f3ff;padding:15px;border-radius:8px;text-align:center;font-size:24px;font-weight:bold;color:#1a237e;'>" +
            parkingCode + "</div><br>" +
            "השתמש בקוד זה כדי לצאת מהחניון או לבצע פעולות נוספות.",
            "<strong>אבטחה:</strong> אל תשתף קוד זה עם אחרים. הוא תקף רק עבור ההזמנה הנוכחית שלך.",
            "#d1ecf1", "#17a2b8"
        );
        return new EmailContent(subject, content);
    }
    
    /**
     * Create extension confirmation content
     */
    private static EmailContent createExtensionContent(String customerName, String parkingCode, int hours, String newEndTime) {
        String subject = "אישור הארכת חניה - קוד " + parkingCode;
        String content = createEmailTemplate(customerName, LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), 
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
            "אישור הארכת חניה",
            "שלום " + customerName + ",",
            "הארכת החניה שלך אושרה בהצלחה!<br><br>" +
            "<strong>קוד חניה:</strong> " + parkingCode + "<br>" +
            "<strong>זמן הארכה:</strong> " + hours + " שעות<br>" +
            "<strong>זמן סיום חדש:</strong> " + newEndTime + "<br><br>" +
            "תוכל כעת להישאר בחניון עד לזמן החדש.",
            "<strong>תזכורת:</strong> אנא הקפד לצאת עד לזמן החדש כדי למנוע חיובים נוספים.",
            "#d4edda", "#28a745"
        );
        return new EmailContent(subject, content);
    }
    
    /**
     * Create expired parking notification content
     */
    private static EmailContent createExpiredContent(String customerName, String spotNumber, String date, String time) {
        String subject = "הודעה על פקיעת זמן חניה - " + date;
        String content = createEmailTemplate(customerName, date, time,
            "הודעה על פקיעת זמן חניה",
            "שלום " + customerName + ",",
            "זמן החניה שלך פג במקום " + spotNumber + ".<br><br>" +
            "אנא הגע לאסוף את רכבך בהקדם האפשרי.<br>" +
            "החל מרגע זה עלולים לחול חיובים נוספים.",
            "<strong>חשוב:</strong> יש לפנות את מקום החניה כדי לא לחסום אותו עבור לקוחות אחרים.",
            "#fff3cd", "#ffc107"
        );
        return new EmailContent(subject, content);
    }
    
    /**
     * Create welcome message content - UPDATED WITH USER_ID
     */
    private static EmailContent createWelcomeContent(String customerName, String username, int userID) {
        String subject = "ברוכים הבאים ל-BPARK - מערכת חניון חכמה!";
        String content = createEmailTemplate(customerName, LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), 
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
            "ברוכים הבאים ל-BPARK!",
            "שלום " + customerName + " וברוכים הבאים!",
            "אנחנו שמחים שהצטרפת למערכת החניון החכם שלנו.<br><br>" +
            "<strong>מספר מזהה הלקוח שלך הוא:</strong> " + userID + "<br>" +
            "<strong>שם המשתמש שלך:</strong> " + username + "<br><br>" +
            "במערכת שלנו תוכל:<br>" +
            "• להזמין מקומות חניה מראש<br>" +
            "• לנהל הזמנות קיימות<br>" +
            "• לקבל התראות בזמן אמת<br>" +
            "• לשחזר קודי חניה<br>" +
            "• להאריך זמן חניה",
            "<strong>התחל עכשיו:</strong> היכנס למערכת עם שם המשתמש שלך ותתחיל ליהנות מחניה חכמה!",
            "#d4edda", "#28a745"
        );
        return new EmailContent(subject, content);
    }
    
    /**
     * Create default content for unknown types
     */
    private static EmailContent createDefaultContent(String customerName) {
        return new EmailContent("הודעה מ-BPARK", 
            createEmailTemplate(customerName, LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                "הודעה מ-BPARK", "שלום " + customerName + ",", 
                "קיבלת הודעה מצוות BPARK.", "", "#d1ecf1", "#17a2b8"));
    }
    
    /**
     * Create HTML email template (Hebrew RTL design)
     */
    private static String createEmailTemplate(String customerName, String date, String time, 
                                            String title, String greeting, String mainMessage, 
                                            String alertMessage, String alertBgColor, String alertBorderColor) {
        return "<!DOCTYPE html>" +
               "<html dir='rtl'>" +
               "<head>" +
               "<meta charset='UTF-8'>" +
               "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
               "</head>" +
               "<body style='margin:0;padding:20px;background:#f0f0f0;font-family:Arial,sans-serif;'>" +
               
               "<table style='max-width:600px;margin:auto;border:1px solid #eee;font-family:Arial,sans-serif;background:#fff;'>" +
               
               "<tr>" +
               "<td style='padding:0;text-align:center;'>" +
               "<img src='" + LOGO_URL + "' alt='" + COMPANY_NAME + "' style='width:100%;max-width:600px;height:auto;display:block;'>" +
               "</td>" +
               "</tr>" +
               
               "<tr>" +
               "<td style='padding:30px 20px 10px 20px;'>" +
               "<h2 style='color:#1a237e;margin:0 0 16px 0;'>" + title + "</h2>" +
               
               "<p style='font-size:16px;color:#333;margin-bottom:18px;'>" + greeting + "</p>" +
               
               "<div style='background:#f9f9f9;padding:15px;border-right:4px solid #1a237e;margin-bottom:20px;'>" +
               "<p style='margin:0;font-size:15px;color:#444;'>" +
               "<strong>תאריך:</strong> " + date + "<br>" +
               "<strong>שעה:</strong> " + time +
               "</p>" +
               "</div>" +
               
               "<p style='font-size:15px;color:#444;margin-bottom:16px;line-height:1.6;'>" + mainMessage + "</p>" +
               
               (alertMessage.isEmpty() ? "" :
               "<p style='font-size:15px;color:#444;margin-bottom:20px;padding:10px;background:" + alertBgColor + ";border-right:4px solid " + alertBorderColor + ";'>" +
               alertMessage + "</p>") +
               
               "<p style='font-size:15px;color:#444;margin-bottom:20px;'>" +
               "לפרטים נוספים ולסיוע ניתן לפנות אלינו במוקד BPARK בטלפון: " +
               "<strong>" + SUPPORT_PHONE + "</strong><br>" +
               "או במייל: " + 
               "<a href='mailto:" + SUPPORT_EMAIL + "' style='color:#1a237e;text-decoration:none;'>" + SUPPORT_EMAIL + "</a>" +
               "</p>" +
               "</td>" +
               "</tr>" +
               
               "<tr>" +
               "<td style='padding:15px 20px 30px 20px;'>" +
               "<p style='font-size:16px;color:#1a237e;margin:0;font-weight:bold;'>בברכה,<br>צוות BPARK</p>" +
               "</td>" +
               "</tr>" +
               
               "<tr>" +
               "<td style='background:#f5f5f5;text-align:center;padding:15px;color:#999;font-size:12px;'>" +
               "הודעה זו נשלחה באופן אוטומטי ב-" + date + " בשעה " + time + "<br>" +
               "אין להשיב להודעה זו" +
               "</td>" +
               "</tr>" +
               
               "</table>" +
               "</body>" +
               "</html>";
    }
}