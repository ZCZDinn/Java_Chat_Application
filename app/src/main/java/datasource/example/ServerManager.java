package datasource.example;

import java.io.Serializable;
import java.sql.*;
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

@Named("serverManagerBean")
@SessionScoped
public class ServerManager implements Serializable {

    public static class ServerEntry implements Serializable {
        private int serverId;
        private String name;
        private boolean isPublic;
        private boolean joined;
        private String ownerName;

        public ServerEntry(int serverId, String name, boolean isPublic, boolean joined, String ownerName) {
            this.serverId = serverId;
            this.name = name;
            this.isPublic = isPublic;
            this.joined = joined;
            this.ownerName = ownerName;  
        }

        public int getServerId() { return serverId; }
        public String getName() { return name; }
        public boolean getPublicServer() { return isPublic; }
        public boolean isJoined() { return joined; }
        public String getOwnerName() { return ownerName; }
    }

    private String serverName;
    private boolean publicServer = true;
    private String inviteCodeInput = "";
    private String lastGeneratedInviteCode = null;
    private String statusMessage = "";
    private List<ServerEntry> myServers = new LinkedList<>();
    private List<ServerEntry> publicServers = new LinkedList<>();
    private transient Connection conn;

    @Inject private UserLogin login;
    @Inject private ServerView serverView;
    @Inject private CurrentContext currentContext;

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

    // Creates a public or private server. Owner is auto-joined.
    // For private servers, an invite code is auto-generated (UUID).
    public String createServer() {
        ensureConnection();
        int me = login.getUserId();
        lastGeneratedInviteCode = null;

        // Check name first, before preparing the INSERT
        try (PreparedStatement nameCheck = conn.prepareStatement(
                "SELECT 1 FROM servers WHERE name = ?")) {
            nameCheck.setString(1, serverName);
            try (ResultSet rs = nameCheck.executeQuery()) {
                if (rs.next()) {
                    statusMessage = "A server named \"" + serverName + "\" already exists. Please choose a different name.";
                    return null;
                }
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
            return null;
        }
        
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO servers (name, ownerID, isPublic) VALUES (?, ?, ?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ins.setString(1, serverName);
            ins.setInt(2, me);
            ins.setBoolean(3, publicServer);
            ins.executeUpdate();
            int newServerId;
            try (ResultSet keys = ins.getGeneratedKeys()) {
                if (!keys.next()) {
                    statusMessage = "Failed to create server.";
                    return null;
                }
                newServerId = keys.getInt(1);
            }
            // Auto-join the owner as a member
            try (PreparedStatement join = conn.prepareStatement(
                    "INSERT INTO server_members (serverID, userID, joinedAt) VALUES (?, ?, NOW())")) {
                join.setInt(1, newServerId);
                join.setInt(2, me);
                join.executeUpdate();
            }
            
            // Add owner permissions to all channels (none yet, but good for consistency)
            try (PreparedStatement memberPerms = conn.prepareStatement(
                    "INSERT INTO channel_member_perms (channelID, userID, canRead, canWrite) " +
                    "SELECT channelID, ?, true, true FROM channels WHERE serverID = ?")) {
                memberPerms.setInt(1, me);
                memberPerms.setInt(2, newServerId);
                memberPerms.executeUpdate();
            }
            
            // For private servers, insert a row into server_invites.
            // An invite code is automatically generated.
           if (!publicServer) {
                String inviteCode = java.util.UUID.randomUUID().toString();
                try (PreparedStatement invite = conn.prepareStatement(
                        "INSERT INTO server_invites (serverID, inviteCode, createdBy) VALUES (?, ?, ?)")) {
                    invite.setInt(1, newServerId);
                    invite.setString(2, inviteCode);
                    invite.setString(3, login.getUserName());
                    invite.executeUpdate();
                    lastGeneratedInviteCode = inviteCode;
                }
                statusMessage = "Private server \"" + serverName + "\" created! Invite code: " + lastGeneratedInviteCode;
            } else {
                statusMessage = "Public server \"" + serverName + "\" created!";
            }
            loadMyServers();
            
            // Set context and navigate to server
            currentContext.setCurrentServerID(newServerId);
            return serverView.loadServer(newServerId);
        } catch (SQLException e) {
            statusMessage = e.getMessage();
            return null;
        }
    }

    // Loads all servers the current user belongs to
    public void loadMyServers() {
        ensureConnection();
        myServers.clear();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT s.serverID, s.name, s.isPublic, u.userName " +
                "FROM servers s " +
                "JOIN server_members sm ON s.serverID = sm.serverID " +
                "JOIN users u ON s.ownerID = u.userID " +
                "WHERE sm.userID = ?")) {
            stmt.setInt(1, login.getUserId());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    myServers.add(new ServerEntry(rs.getInt(1), rs.getString(2), rs.getBoolean(3), true, rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    // Loads all public servers for browsing
    public void loadPublicServers() {
        ensureConnection();
        publicServers.clear();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT s.serverID, s.name, u.userName, " +
                "CASE WHEN sm.userID IS NOT NULL THEN TRUE ELSE FALSE END AS isMember " +
                "FROM servers s " +
                "JOIN users u ON s.ownerID = u.userID " +
                "LEFT JOIN server_members sm ON s.serverID = sm.serverID AND sm.userID = ? " +
                "WHERE s.isPublic = TRUE")) {
            stmt.setInt(1, login.getUserId());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    publicServers.add(new ServerEntry(rs.getInt(1), rs.getString(2), true, rs.getBoolean(4), rs.getString(3)));
                }
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    // Joins a public server from the browse page, navigates to server.xhtml
    public String joinPublicServer(int serverId) {
        ensureConnection();
        int me = login.getUserId();
        try {
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT isPublic FROM servers WHERE serverID = ?")) {
                check.setInt(1, serverId);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean(1)) {
                        statusMessage = "Server not found or is not public.";
                        return null;
                    }
                }
            }
            // If already a member just open it
            try (PreparedStatement memberCheck = conn.prepareStatement(
                    "SELECT 1 FROM server_members WHERE serverID = ? AND userID = ?")) {
                memberCheck.setInt(1, serverId);
                memberCheck.setInt(2, me);
                try (ResultSet rs = memberCheck.executeQuery()) {
                    if (rs.next()) {
                        return serverView.loadServer(serverId);
                    }
                }
            }
            try (PreparedStatement join = conn.prepareStatement(
                    "INSERT INTO server_members (serverID, userID, joinedAt) VALUES (?, ?, NOW())")) {
                join.setInt(1, serverId);
                join.setInt(2, me);
                join.executeUpdate();
            }
            
            // Add member permissions to all channels in this server
            try (PreparedStatement memberPerms = conn.prepareStatement(
                    "INSERT INTO channel_member_perms (channelID, userID, canRead, canWrite) " +
                    "SELECT channelID, ?, true, true FROM channels WHERE serverID = ?")) {
                memberPerms.setInt(1, me);
                memberPerms.setInt(2, serverId);
                memberPerms.executeUpdate();
            }
            
            loadMyServers();
            return serverView.loadServer(serverId);
        } catch (SQLException e) {
            statusMessage = e.getMessage();
            return null;
        }
    }

    // Req #5 — Joins a private server using an integer invite code
    public String joinByInviteCode() {
        ensureConnection();
        int me = login.getUserId();
        try {
            int serverId;
            try (PreparedStatement find = conn.prepareStatement(
                    "SELECT serverID FROM server_invites WHERE inviteCode = ?")) {
                find.setString(1, inviteCodeInput);
                try (ResultSet rs = find.executeQuery()) {
                    if (!rs.next()) {
                        statusMessage = "Invalid invite code.";
                        return null;
                    }
                    serverId = rs.getInt(1);
                }
            }
            // If already a member just open it
            try (PreparedStatement memberCheck = conn.prepareStatement(
                    "SELECT 1 FROM server_members WHERE serverID = ? AND userID = ?")) {
                memberCheck.setInt(1, serverId);
                memberCheck.setInt(2, me);
                try (ResultSet rs = memberCheck.executeQuery()) {
                    if (rs.next()) {
                        return serverView.loadServer(serverId);
                    }
                }
            }
            try (PreparedStatement join = conn.prepareStatement(
                    "INSERT INTO server_members (serverID, userID, joinedAt) VALUES (?, ?, NOW())")) {
                join.setInt(1, serverId);
                join.setInt(2, me);
                join.executeUpdate();
            }
            
            // Add member permissions to all channels in this server
            try (PreparedStatement memberPerms = conn.prepareStatement(
                    "INSERT INTO channel_member_perms (channelID, userID, canRead, canWrite) " +
                    "SELECT channelID, ?, true, true FROM channels WHERE serverID = ?")) {
                memberPerms.setInt(1, me);
                memberPerms.setInt(2, serverId);
                memberPerms.executeUpdate();
            }
            
            loadMyServers();
            return serverView.loadServer(serverId);
        } catch (SQLException e) {
            statusMessage = e.getMessage();
            return null;
        }
    }

    // Opens a server the user already belongs to
    public String openServer(int serverId) {
        currentContext.setCurrentServerID(serverId);
        return serverView.loadServer(serverId);
    }

    // Sets the current server in context (for channel operations)
    public void selectServer(int serverId) {
        currentContext.setCurrentServerID(serverId);
    }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public boolean isPublicServer() { return publicServer; }
    public void setPublicServer(boolean publicServer) { this.publicServer = publicServer; }
    public String getInviteCodeInput() { return inviteCodeInput; }
    public void setInviteCodeInput(String inviteCodeInput) { this.inviteCodeInput = inviteCodeInput; }
    public String getLastGeneratedInviteCode() { return lastGeneratedInviteCode; }
    public String getStatusMessage() { return statusMessage; }
    public List<ServerEntry> getMyServers() { return myServers; }
    public List<ServerEntry> getPublicServers() { return publicServers; }
}
