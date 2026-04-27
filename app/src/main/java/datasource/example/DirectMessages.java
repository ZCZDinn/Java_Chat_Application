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
    private int dmTargetIdInput;
    private int inviteTargetIdInput;
    private String inviteServerName = "";
    private String inviteCodeToSend = "";
    private Connection conn;
    @Inject private UserLogin login;

    @PostConstruct
    public void openConnection() {
        try {
            Context ctx = new InitialContext();
            DataSource ds = (DataSource) ctx.lookup("java:/comp/env/jdbc/FinalJava");
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

    // Looks up an existing private conversation with the target user, or creates one if it doesn't exist yet.
    // Returns -1 if messaging is blocked or something goes wrong.
    private int getOrCreateDm(int targetId) {
        int me = login.getUserId();
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
                    statusMessage = "Cannot message this user: a block exists between you.";
                    return -1;
                }
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
            return -1;
        }

        // Store the lower user ID first so there's only ever one conversation record per pair of users
        int user1 = Math.min(me, targetId);
        int user2 = Math.max(me, targetId);

        try {
            try (PreparedStatement find = conn.prepareStatement(
                    "SELECT dmID FROM direct_messages WHERE user1ID = ? AND user2ID = ?")) {
                find.setInt(1, user1);
                find.setInt(2, user2);
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
            // No existing conversation found, so create a new one
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO direct_messages (user1ID, user2ID) VALUES (?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ins.setInt(1, user1);
                ins.setInt(2, user2);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    if (keys.next()) return keys.getInt(1);
                }
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
        return -1;
    }

    // Opens a direct message conversation with the user ID entered in the DM input field on the friends page
    public String openDmByInput() {
        return openDmWith(dmTargetIdInput);
    }

    // Opens a direct message conversation with a specific user (used from the friends list DM button)
    public String openDmWith(int targetId) {
        int dmId = getOrCreateDm(targetId);
        if (dmId < 0) return null;
        currentDmId = dmId;
        loadMessages();
        return "dm";
    }

    // Loads all messages from the current conversation so they can be displayed on the DM page
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

    // Sends the typed message to the other person in the current conversation and refreshes the chat
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

    // Sends a private server invite to another user as a DM message containing the invite code
    public void sendInviteDm() {
        if (inviteCodeToSend == null || inviteCodeToSend.isBlank()) {
            statusMessage = "Please enter an invite code.";
            return;
        }
        int dmId = getOrCreateDm(inviteTargetIdInput);
        if (dmId < 0) return;
        String serverLabel = (inviteServerName != null && !inviteServerName.isBlank())
                ? "\"" + inviteServerName + "\""
                : "a private server";
        String msg = "You've been invited to join " + serverLabel + "! Invite code: " + inviteCodeToSend;
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO messages (message, sentOn, userID, dmID) VALUES (?, ?, ?, ?)")) {
            stmt.setString(1, msg);
            stmt.setTimestamp(2, Timestamp.from(Instant.now()));
            stmt.setInt(3, login.getUserId());
            stmt.setInt(4, dmId);
            stmt.executeUpdate();
            statusMessage = "Invite sent!";
            inviteCodeToSend = "";
            inviteServerName = "";
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    public String getMessageText() { return messageText; }
    public void setMessageText(String messageText) { this.messageText = messageText; }
    public String getStatusMessage() { return statusMessage; }
    public List<String> getDmLog() { return dmLog; }
    public int getCurrentDmId() { return currentDmId; }
    public int getDmTargetIdInput() { return dmTargetIdInput; }
    public void setDmTargetIdInput(int dmTargetIdInput) { this.dmTargetIdInput = dmTargetIdInput; }
    public int getInviteTargetIdInput() { return inviteTargetIdInput; }
    public void setInviteTargetIdInput(int inviteTargetIdInput) { this.inviteTargetIdInput = inviteTargetIdInput; }
    public String getInviteServerName() { return inviteServerName; }
    public void setInviteServerName(String inviteServerName) { this.inviteServerName = inviteServerName; }
    public String getInviteCodeToSend() { return inviteCodeToSend; }
    public void setInviteCodeToSend(String inviteCodeToSend) { this.inviteCodeToSend = inviteCodeToSend; }
}
