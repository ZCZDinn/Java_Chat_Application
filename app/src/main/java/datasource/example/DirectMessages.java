package datasource.example;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named("directMessagesBean")
@SessionScoped
public class DirectMessages implements Serializable {

    private String messageText;
    private String statusMessage = "";
    private List<String> dmLog = new LinkedList<>();
    private int currentDmId = -1;
    private Connection conn;
    @Inject private UserLogin login;

    @PostConstruct
    public void openConnection() {
        try {
            Context ctx = new InitialContext();
            DataSource ds = (DataSource) ctx.lookup("java:/comp/env/jdbc/Assignment2");
            conn = ds.getConnection();
        } catch (NamingException | SQLException e) {
            System.out.println(e.getMessage());
        }
    }

    @PreDestroy
    public void closeConnection() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    // Opens (or creates) a DM conversation with the given user.
    // Checks for a block relationship first; navigates to dm.xhtml on success.
    public String openDmWith(int targetId) {
        int me = login.getUserId();

        // Check if either user has blocked the other
        try (PreparedStatement blockCheck = conn.prepareStatement(
                "SELECT 1 FROM friends " +
                "WHERE ((userID = ? AND friendID = ?) OR (userID = ? AND friendID = ?)) " +
                "AND status = 'blocked'")) {
            blockCheck.setInt(1, me);
            blockCheck.setInt(2, targetId);
            blockCheck.setInt(3, targetId);
            blockCheck.setInt(4, me);
            try (ResultSet rs = blockCheck.executeQuery()) {
                if (rs.next()) {
                    statusMessage = "Cannot open DM: one of the users has blocked the other.";
                    return null;
                }
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
            return null;
        }

        // Ensure the lower ID is always user1ID so each pair has exactly one row
        int user1 = Math.min(me, targetId);
        int user2 = Math.max(me, targetId);

        try {
            // Try to find an existing DM conversation between these two users
            try (PreparedStatement find = conn.prepareStatement(
                    "SELECT dmID FROM direct_messages WHERE user1ID = ? AND user2ID = ?")) {
                find.setInt(1, user1);
                find.setInt(2, user2);
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) {
                        currentDmId = rs.getInt(1);
                    } else {
                        // No conversation exists yet — create one
                        try (PreparedStatement ins = conn.prepareStatement(
                                "INSERT INTO direct_messages (user1ID, user2ID) VALUES (?, ?)",
                                PreparedStatement.RETURN_GENERATED_KEYS)) {
                            ins.setInt(1, user1);
                            ins.setInt(2, user2);
                            ins.executeUpdate();
                            try (ResultSet keys = ins.getGeneratedKeys()) {
                                if (keys.next()) {
                                    currentDmId = keys.getInt(1);
                                }
                            }
                        }
                    }
                }
            }
            loadMessages();
            return "dm";
        } catch (SQLException e) {
            statusMessage = e.getMessage();
            return null;
        }
    }

    // Loads all messages for the current DM conversation into dmLog
    public void loadMessages() {
        dmLog.clear();
        if (currentDmId < 0) return;
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT u.userName, m.message, m.sentOn " +
                "FROM messages m JOIN users u ON m.userID = u.userID " +
                "WHERE m.dmID = ? ORDER BY m.sentOn ASC")) {
            stmt.setInt(1, currentDmId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    dmLog.add("[" + rs.getTimestamp(3) + "] " + rs.getString(1) + ": " + rs.getString(2));
                }
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    // Sends a message in the current DM conversation
    public void sendMessage() {
        if (currentDmId < 0 || messageText == null || messageText.isBlank()) return;
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO messages (message, sentOn, userID, dmID) VALUES (?, ?, ?, ?)")) {
            stmt.setString(1, messageText);
            stmt.setTimestamp(2, Timestamp.from(Instant.now()));
            stmt.setInt(3, login.getUserId());
            stmt.setInt(4, currentDmId);
            stmt.executeUpdate();
            messageText = "";
            loadMessages();
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    public String getMessageText() { return messageText; }
    public void setMessageText(String messageText) { this.messageText = messageText; }
    public String getStatusMessage() { return statusMessage; }
    public List<String> getDmLog() { return dmLog; }
    public int getCurrentDmId() { return currentDmId; }
}
