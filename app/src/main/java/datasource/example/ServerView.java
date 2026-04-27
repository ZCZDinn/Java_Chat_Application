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
import jakarta.inject.Named;
import jakarta.inject.Inject;

@Named("serverViewBean")
@SessionScoped
public class ServerView implements Serializable {

    // One row per user-role pair. Users with no assigned roles appear once with roleName "Owner" if the owner, "Member" otherwise.
    public static class MemberEntry implements Serializable {
        private int userId;
        private String userName;
        private String roleName;
        private boolean owner;  

        public MemberEntry(int userId, String userName, String roleName, boolean owner) {
            this.userId = userId;
            this.userName = userName;
            this.roleName = roleName;
            this.owner = owner;   
        }

        public int getUserId() { return userId; }
        public String getUserName() { return userName; }
        public String getRoleName() {
            if (owner) return "Owner";
            return roleName != null ? roleName : "Member";
        }
    }

    private int appointOwnerId = -1;
    private String appointMessage = ""; 
    private int currentServerId = -1;
    private String currentServerName = "";
    private boolean currentServerPublic;
    private String statusMessage = "";
    private List<MemberEntry> members = new LinkedList<>();
    private Connection conn;
    private int ownerID = -1;  // add this field
    @Inject private UserLogin login;
    private String currentInviteCode = null;

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

    // Called by ServerManager to set the active server and navigate to server.xhtml
    public String loadServer(int serverId) {
        currentServerId = serverId;
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT name, isPublic, ownerID FROM servers WHERE serverID = ?")) {
            stmt.setInt(1, serverId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    currentServerName = rs.getString(1);
                    currentServerPublic = rs.getBoolean(2);
                    ownerID = rs.getInt(3);
                }
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
        loadMembers();
        loadInviteCode();
        return "server";
    }

    // Req #12 — Loads all members with their server-specific roles (LEFT JOIN so no-role users appear)
    public void loadMembers() {
        members.clear();
        if (currentServerId < 0) return;
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT u.userID, u.userName, r.name " +
                "FROM server_members sm " +
                "JOIN users u ON sm.userID = u.userID " +
                "LEFT JOIN user_roles ur ON ur.userID = u.userID " +
                "LEFT JOIN roles r ON r.roleID = ur.roleID AND r.serverID = ? " +
                "WHERE sm.serverID = ? " +
                "ORDER BY u.userName ASC")) {
            stmt.setInt(1, currentServerId);
            stmt.setInt(2, currentServerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    members.add(new MemberEntry(
                        rs.getInt(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(1) == ownerID   // true if this member is the owner
                    ));
                }
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    public void loadInviteCode() {
        currentInviteCode = null;
        if (currentServerId < 0 || currentServerPublic) return;
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT inviteCode FROM server_invites WHERE serverID = ? LIMIT 1")) {
            stmt.setInt(1, currentServerId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    currentInviteCode = rs.getString(1);
                }
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    public void regenerateInviteCode() {
        if (currentServerId < 0 || login.getUserId() != ownerID) return;
        String newCode = java.util.UUID.randomUUID().toString();
        try {
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM server_invites WHERE serverID = ?")) {
                del.setInt(1, currentServerId);
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO server_invites (serverID, inviteCode, createdBy) VALUES (?, ?, ?)")) {
                ins.setInt(1, currentServerId);
                ins.setString(2, newCode);
                ins.setString(3, login.getUserName());
                ins.executeUpdate();
            }
            currentInviteCode = newCode;
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    public String leaveServer() {
        if (currentServerId < 0) return "servers?faces-redirect=true";
        try (PreparedStatement stmt = conn.prepareStatement(
                "DELETE FROM server_members WHERE serverID = ? AND userID = ?")) {
            stmt.setInt(1, currentServerId);
            stmt.setInt(2, login.getUserId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            statusMessage = e.getMessage();
            return null;
        }
        currentServerId = -1;
        return "servers?faces-redirect=true";
    }

    public List<MemberEntry> getNonOwnerMembers() {
        List<MemberEntry> result = new LinkedList<>();
        for (MemberEntry m : members) {
            if (m.getUserId() != ownerID) result.add(m);
        }
        return result;
    }

    public void appointNewOwner() {
        if (appointOwnerId == -1) {
            appointMessage = "Please select a member.";
            return;
        }
        if (currentServerId < 0 || login.getUserId() != ownerID) return;
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE servers SET ownerID = ? WHERE serverID = ?")) {
            stmt.setInt(1, appointOwnerId);
            stmt.setInt(2, currentServerId);
            stmt.executeUpdate();
            ownerID = appointOwnerId;
            appointOwnerId = -1;
            appointMessage = "";
            loadMembers();
            loadInviteCode();
        } catch (SQLException e) {
            appointMessage = e.getMessage();
        }
    }

    public int getAppointOwnerId() { return appointOwnerId; }
    public void setAppointOwnerId(int appointOwnerId) { this.appointOwnerId = appointOwnerId; }
    public String getAppointMessage() { return appointMessage; }
    public int getCurrentServerId() { return currentServerId; }
    public String getCurrentServerName() { return currentServerName; }
    public boolean isCurrentServerPublic() { return currentServerPublic; }
    public String getStatusMessage() { return statusMessage; }
    public List<MemberEntry> getMembers() { return members; }
    public int getOwnerID() { return ownerID; }
    public String getCurrentInviteCode() { return currentInviteCode; }
}