package datasource.example;
import datasource.example.ImageUpload;
import java.io.Serializable;
import java.sql.*;
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

@Named("channelChatBean")
@SessionScoped
public class ChannelChat implements Serializable {

    private static final long serialVersionUID = 1L;
    private String messageText;
    private String statusMessage = "";
    private String newChannelName;
    private List<MessageEntry> chatLog = new LinkedList<>();
    private List<Integer> channelIds = new LinkedList<>();
    private List<String> channelNames = new LinkedList<>();

    private int currentChannelId = -1;

    private transient Connection conn;

    @Inject private UserLogin login;
    @Inject private ImageUpload imageUpload;
    @Inject private CurrentContext currentContext;

    // ----------------------------
    // CONNECTION SETUP
    // ----------------------------
    @PostConstruct
    public void openConnection() {
        try {
            Context ctx = new InitialContext();
            DataSource ds = (DataSource) ctx.lookup("java:/comp/env/jdbc/FinalJava");
            conn = ds.getConnection();
        } catch (NamingException | SQLException e) {
            statusMessage = "Connection error: " + e.getMessage();
            System.out.println(e.getMessage());
        }
    }

    @PreDestroy
    public void closeConnection() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
    }

    private void ensureConnection() {
        if (conn == null) {
            try {
                Context ctx = new InitialContext();
                DataSource ds = (DataSource) ctx.lookup("java:/comp/env/jdbc/FinalJava");
                conn = ds.getConnection();
            } catch (NamingException | SQLException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    // ----------------------------
    // CHANNELS
    // ----------------------------
    public void openChannel() {
        ensureConnection();
        loadMessages();
    }

    public void loadChannels() {
        ensureConnection();
        channelIds.clear();
        channelNames.clear();

        if (currentContext == null || currentContext.getCurrentServerID() <= 0) {
            return; // No server selected yet
        }

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT channelID, name FROM channels WHERE serverID = ?")) {

            stmt.setInt(1, currentContext.getCurrentServerID());
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                channelIds.add(rs.getInt("channelID"));
                channelNames.add(rs.getString("name"));
            }

        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    // ----------------------------
    // MESSAGES
    // ----------------------------
    public void loadMessages() {
        ensureConnection();
        chatLog.clear();

        if (currentChannelId < 0) return;

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT m.messageID, u.userName, m.message, m.sentOn, m.imageURL " +
                "FROM messages m " +
                "JOIN users u ON m.userID = u.userID " +
                "WHERE m.channelID = ? ORDER BY m.sentOn ASC")) {

            stmt.setInt(1, currentChannelId);

            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                chatLog.add(new MessageEntry(
                        rs.getInt("messageID"),
                        "[" + rs.getTimestamp("sentOn") + "] "
                                + rs.getString("userName") + ": "
                                + rs.getString("message"),
                        rs.getString("imageURL")
                ));
            }

        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    public void sendMessage() {
        ensureConnection();
        if (!canWrite()) return;

        String imagePath = imageUpload.upload();

        // allow text OR image
        if (currentChannelId < 0 ||
           ((messageText == null || messageText.isBlank()) && imagePath == null)) return;

        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO messages (message, sentOn, userID, channelID, imageURL) VALUES (?, ?, ?, ?, ?)")) {

            stmt.setString(1, messageText);
            stmt.setTimestamp(2, Timestamp.from(Instant.now()));
            stmt.setInt(3, login.getUserId());
            stmt.setInt(4, currentChannelId);
            stmt.setString(5, imagePath);

            stmt.executeUpdate();

            messageText = "";
            loadMessages();

        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    public void deleteMessage(int messageId) {
        ensureConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "DELETE FROM messages WHERE messageID = ? AND userID = ?")) {

            stmt.setInt(1, messageId);
            stmt.setInt(2, login.getUserId());
            stmt.executeUpdate();

        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }

        loadMessages();
    }

    // ----------------------------
    // PERMISSIONS
    // ----------------------------
    public boolean canWrite() {
        ensureConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT crp.canWrite " +
                "FROM user_roles ur " +
                "JOIN roles r ON ur.roleID = r.roleID " +
                "JOIN channel_role_perms crp ON r.roleID = crp.roleID " +
                "WHERE ur.userID = ? AND crp.channelID = ?")) {

            stmt.setInt(1, login.getUserId());
            stmt.setInt(2, currentChannelId);

            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                if (rs.getBoolean("canWrite")) return true;
            }

        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }

        return false;
    }

    public boolean canRead() {
        ensureConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT crp.canRead " +
                "FROM user_roles ur " +
                "JOIN roles r ON ur.roleID = r.roleID " +
                "JOIN channel_role_perms crp ON r.roleID = crp.roleID " +
                "WHERE ur.userID = ? AND crp.channelID = ?")) {

            stmt.setInt(1, login.getUserId());
            stmt.setInt(2, currentChannelId);

            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                if (rs.getBoolean("canRead")) return true;
            }

        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }

        return false;
    }

    // ----------------------------
    // INNER CLASS
    // ----------------------------
    public static class MessageEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        public int id;
        public String text;
        public String imageURL;

        public MessageEntry(int id, String text, String imageURL) {
            this.id = id;
            this.text = text;
            this.imageURL = imageURL;
        }

        public int getId() { return id; }
        public String getText() { return text; }
        public String getImageURL() { return imageURL; }
    }

    // ----------------------------
    // CHANNEL MANAGEMENT
    // ----------------------------
    public void createChannel() {
        ensureConnection();
        if (newChannelName == null || newChannelName.isBlank()) return;

        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO channels (serverID, name) VALUES (?, ?)")) {

            stmt.setInt(1, currentContext.getCurrentServerID());
            stmt.setString(2, newChannelName);
            stmt.executeUpdate();

            newChannelName = "";
            loadChannels(); // refresh sidebar

        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    // ----------------------------
    // GETTERS / SETTERS
    // ----------------------------
    public String getMessageText() { return messageText; }
    public void setMessageText(String messageText) { this.messageText = messageText; }

    public String getStatusMessage() { return statusMessage; }

    public List<MessageEntry> getChatLog() { return chatLog; }

    public int getCurrentChannelId() { return currentChannelId; }
    public void setCurrentChannelId(int currentChannelId) { this.currentChannelId = currentChannelId; }

    public List<Integer> getChannelIds() { return channelIds; }
    public List<String> getChannelNames() { return channelNames; }
    public String getNewChannelName() { return newChannelName; }
    public void setNewChannelName(String newChannelName) { this.newChannelName = newChannelName; }
}