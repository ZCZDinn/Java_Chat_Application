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
    private List<Integer> roleIds = new LinkedList<>();
    private List<String> roleNames = new LinkedList<>();
    private List<Boolean> roleCanRead = new LinkedList<>();
    private List<Boolean> roleCanWrite = new LinkedList<>();

    private int currentChannelId = -1;
    private int lastServerIdLoaded = -1;

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
        loadRolePermissions();
    }

    public void loadRolePermissions() {
        ensureConnection();
        roleIds.clear();
        roleNames.clear();
        roleCanRead.clear();
        roleCanWrite.clear();

        if (currentChannelId < 0) return;

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT r.roleID, r.name, COALESCE(crp.canRead, false) as canRead, COALESCE(crp.canWrite, false) as canWrite " +
                "FROM roles r " +
                "LEFT JOIN channel_role_perms crp ON r.roleID = crp.roleID AND crp.channelID = ? " +
                "WHERE r.serverID = ?")) {

            stmt.setInt(1, currentChannelId);
            stmt.setInt(2, currentContext.getCurrentServerID());
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                roleIds.add(rs.getInt("roleID"));
                roleNames.add(rs.getString("name"));
                roleCanRead.add(rs.getBoolean("canRead"));
                roleCanWrite.add(rs.getBoolean("canWrite"));
            }

        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    public void loadChannels() {
        ensureConnection();
        
        int currentServerId = currentContext == null ? 0 : currentContext.getCurrentServerID();
        
        // Only clear chat and role permissions if we're switching to a different server
        if (lastServerIdLoaded != currentServerId) {
            chatLog.clear();
            roleIds.clear();
            roleNames.clear();
            roleCanRead.clear();
            roleCanWrite.clear();
            lastServerIdLoaded = currentServerId;
        }
        
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

        if (currentChannelId < 0 || currentContext.getCurrentServerID() <= 0) return;

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT m.messageID, m.userID, u.userName, m.message, m.sentOn, m.imageData, m.imageMimeType " +
                "FROM messages m " +
                "JOIN users u ON m.userID = u.userID " +
                "JOIN channels c ON m.channelID = c.channelID " +
                "WHERE m.channelID = ? AND c.serverID = ? ORDER BY m.sentOn ASC")) {

            stmt.setInt(1, currentChannelId);
            stmt.setInt(2, currentContext.getCurrentServerID());

            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                chatLog.add(new MessageEntry(
                        rs.getInt("messageID"),
                        rs.getInt("userID"),
                        "[" + rs.getTimestamp("sentOn") + "] "
                                + rs.getString("userName") + ": "
                                + rs.getString("message"),
                        rs.getBytes("imageData"),
                        rs.getString("imageMimeType")
                ));
            }

        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    public void sendMessage() {
        ensureConnection();
        if (!canWrite()) return;

        imageUpload.upload();
        byte[] imageData = imageUpload.getImageData();
        String imageMimeType = imageUpload.getImageMimeType();

        // allow text OR image
        if (currentChannelId < 0 ||
           ((messageText == null || messageText.isBlank()) && imageData == null)) return;

        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO messages (message, sentOn, userID, channelID, imageData, imageMimeType) VALUES (?, ?, ?, ?, ?, ?)")) {

            stmt.setString(1, messageText);
            stmt.setTimestamp(2, java.sql.Timestamp.from(Instant.now()));
            stmt.setInt(3, login.getUserId());
            stmt.setInt(4, currentChannelId);
            stmt.setBytes(5, imageData);
            stmt.setString(6, imageMimeType);

            stmt.executeUpdate();

            messageText = "";
            loadMessages();

        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    public void deleteMessage(int messageId) {
        ensureConnection();
        
        // Owner can delete any message, others can only delete their own
        try {
            if (isServerOwner()) {
                // Owner can delete any message
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM messages WHERE messageID = ?")) {
                    stmt.setInt(1, messageId);
                    stmt.executeUpdate();
                }
            } else {
                // Non-owner can only delete their own messages
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM messages WHERE messageID = ? AND userID = ?")) {
                    stmt.setInt(1, messageId);
                    stmt.setInt(2, login.getUserId());
                    stmt.executeUpdate();
                }
            }
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
        
        // Owner always has write access
        if (isServerOwner()) return true;
        
        // Check direct member permissions
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT canWrite FROM channel_member_perms WHERE channelID = ? AND userID = ?")) {
            stmt.setInt(1, currentChannelId);
            stmt.setInt(2, login.getUserId());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return rs.getBoolean("canWrite");
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
        
        // Check role-based permissions
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
        
        // Owner always has read access
        if (isServerOwner()) return true;
        
        // Check direct member permissions
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT canRead FROM channel_member_perms WHERE channelID = ? AND userID = ?")) {
            stmt.setInt(1, currentChannelId);
            stmt.setInt(2, login.getUserId());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return rs.getBoolean("canRead");
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
        
        // Check role-based permissions
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
        public int authorId;
        public String text;
        public byte[] imageData;
        public String imageMimeType;

        public MessageEntry(int id, int authorId, String text, byte[] imageData, String imageMimeType) {
            this.id = id;
            this.authorId = authorId;
            this.text = text;
            this.imageData = imageData;
            this.imageMimeType = imageMimeType;
        }

        public int getId() { return id; }
        public int getAuthorId() { return authorId; }
        public String getText() { return text; }
        public byte[] getImageData() { return imageData; }
        public String getImageMimeType() { return imageMimeType; }
        public boolean hasImage() { return imageData != null && imageData.length > 0; }
    }

    // ----------------------------
    // CHANNEL MANAGEMENT
    // ----------------------------
    public void createChannel() {
        ensureConnection();
        if (newChannelName == null || newChannelName.isBlank()) return;

        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO channels (serverID, name) VALUES (?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {

            stmt.setInt(1, currentContext.getCurrentServerID());
            stmt.setString(2, newChannelName);
            stmt.executeUpdate();

            // Get the new channel ID
            ResultSet rs = stmt.getGeneratedKeys();
            int newChannelId = -1;
            if (rs.next()) {
                newChannelId = rs.getInt(1);
            }

            // Grant permissions
            if (newChannelId > 0) {
                // 1. Grant role-based permissions to all roles
                try (PreparedStatement permStmt = conn.prepareStatement(
                        "INSERT INTO channel_role_perms (channelID, roleID, canRead, canWrite) " +
                        "SELECT ?, roleID, true, true FROM roles WHERE serverID = ?")) {
                    permStmt.setInt(1, newChannelId);
                    permStmt.setInt(2, currentContext.getCurrentServerID());
                    permStmt.executeUpdate();
                }
                
                // 2. Grant direct permissions to all members in the server
                try (PreparedStatement memberStmt = conn.prepareStatement(
                        "INSERT INTO channel_member_perms (channelID, userID, canRead, canWrite) " +
                        "SELECT ?, userID, true, true FROM server_members WHERE serverID = ?")) {
                    memberStmt.setInt(1, newChannelId);
                    memberStmt.setInt(2, currentContext.getCurrentServerID());
                    memberStmt.executeUpdate();
                }
            }

            newChannelName = "";
            loadChannels(); // refresh sidebar

        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    public boolean isServerOwner() {
        ensureConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT ownerID FROM servers WHERE serverID = ?")) {
            stmt.setInt(1, currentContext.getCurrentServerID());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("ownerID") == login.getUserId();
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
        return false;
    }

    public void updateChannelPermissions(int roleId, boolean canRead, boolean canWrite) {
        ensureConnection();
        
        // Only server owner can change permissions
        if (!isServerOwner()) {
            statusMessage = "Only server owner can change permissions";
            return;
        }

        try {
            // First try to update
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE channel_role_perms SET canRead = ?, canWrite = ? WHERE channelID = ? AND roleID = ?")) {
                stmt.setBoolean(1, canRead);
                stmt.setBoolean(2, canWrite);
                stmt.setInt(3, currentChannelId);
                stmt.setInt(4, roleId);
                int rows = stmt.executeUpdate();
                
                // If no rows updated, insert new permission
                if (rows == 0) {
                    try (PreparedStatement insertStmt = conn.prepareStatement(
                            "INSERT INTO channel_role_perms (channelID, roleID, canRead, canWrite) VALUES (?, ?, ?, ?)")) {
                        insertStmt.setInt(1, currentChannelId);
                        insertStmt.setInt(2, roleId);
                        insertStmt.setBoolean(3, canRead);
                        insertStmt.setBoolean(4, canWrite);
                        insertStmt.executeUpdate();
                    }
                }
            }
            loadRolePermissions(); // refresh
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

    public List<Integer> getRoleIds() { return roleIds; }
    public List<String> getRoleNames() { return roleNames; }
    public List<Boolean> getRoleCanRead() { return roleCanRead; }
    public List<Boolean> getRoleCanWrite() { return roleCanWrite; }
    public boolean getServerOwner() { 
        return isServerOwner();
    }

    public boolean canDeleteMessage(int authorId) {
        return isServerOwner() || login.getUserId() == authorId;
    }
}