package datasource.example;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

@Named("friendsBean")
@SessionScoped
public class Friends implements Serializable {

    // Inner class used to hold a friend's ID and username for display in XHTML
    public static class FriendEntry implements Serializable {
        private int userId;
        private String userName;

        public FriendEntry(int userId, String userName) {
            this.userId = userId;
            this.userName = userName;
        }

        public int getUserId() { return userId; }
        public String getUserName() { return userName; }
    }

    private int friendIdInput;
    private String statusMessage = "";
    private List<FriendEntry> friendList = new LinkedList<>();
    private List<FriendEntry> pendingRequests = new LinkedList<>();
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

    // Loads all accepted friends into friendList
    public void loadFriends() {
        friendList.clear();
        try (
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT u.userID, u.userName FROM friends f " +
                "JOIN users u ON f.friendID = u.userID " +
                "WHERE f.userID = ? AND f.status = 'accepted'");
        ) {
            stmt.setInt(1, login.getUserId());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    friendList.add(new FriendEntry(rs.getInt(1), rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    // Loads all incoming pending friend requests into pendingRequests
    public void loadPendingRequests() {
        pendingRequests.clear();
        try (
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT u.userID, u.userName FROM friends f " +
                "JOIN users u ON f.userID = u.userID " +
                "WHERE f.friendID = ? AND f.status = 'pending'");
        ) {
            stmt.setInt(1, login.getUserId());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    pendingRequests.add(new FriendEntry(rs.getInt(1), rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    // Sends a friend request to the user with ID friendIdInput.
    // If that user already sent us a request, auto-accept instead.
    public void addFriend() {
        int me = login.getUserId();
        if (friendIdInput == me) {
            statusMessage = "You cannot add yourself as a friend.";
            return;
        }
        try {
            // Check if the target has already sent me a pending request
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT status FROM friends WHERE userID = ? AND friendID = ?")) {
                check.setInt(1, friendIdInput);
                check.setInt(2, me);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next() && "pending".equals(rs.getString(1))) {
                        // Auto-accept: update their row and insert our acceptance row
                        try (PreparedStatement upd = conn.prepareStatement(
                                "UPDATE friends SET status = 'accepted' WHERE userID = ? AND friendID = ?")) {
                            upd.setInt(1, friendIdInput);
                            upd.setInt(2, me);
                            upd.executeUpdate();
                        }
                        try (PreparedStatement ins = conn.prepareStatement(
                                "INSERT INTO friends (userID, friendID, status) VALUES (?, ?, 'accepted')")) {
                            ins.setInt(1, me);
                            ins.setInt(2, friendIdInput);
                            ins.executeUpdate();
                        }
                        statusMessage = "Friend request accepted!";
                        loadFriends();
                        loadPendingRequests();
                        return;
                    }
                }
            }
            // Otherwise send a new friend request (ignore if already pending, don't overwrite a block)
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO friends (userID, friendID, status) VALUES (?, ?, 'pending') " +
                    "ON DUPLICATE KEY UPDATE status = IF(status = 'blocked', status, 'pending')")) {
                stmt.setInt(1, me);
                stmt.setInt(2, friendIdInput);
                stmt.executeUpdate();
                statusMessage = "Friend request sent to user " + friendIdInput + "!";
            }
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    // Blocks the user with ID friendIdInput
    public void blockUser() {
        int me = login.getUserId();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO friends (userID, friendID, status) VALUES (?, ?, 'blocked') " +
                "ON DUPLICATE KEY UPDATE status = 'blocked'")) {
            stmt.setInt(1, me);
            stmt.setInt(2, friendIdInput);
            stmt.executeUpdate();
            statusMessage = "User " + friendIdInput + " has been blocked.";
            loadFriends();
            loadPendingRequests();
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    // Accepts an incoming friend request from the given requesterId
    public void acceptFriend(int requesterId) {
        int me = login.getUserId();
        try {
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE friends SET status = 'accepted' WHERE userID = ? AND friendID = ?")) {
                upd.setInt(1, requesterId);
                upd.setInt(2, me);
                upd.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO friends (userID, friendID, status) VALUES (?, ?, 'accepted') " +
                    "ON DUPLICATE KEY UPDATE status = 'accepted'")) {
                ins.setInt(1, me);
                ins.setInt(2, requesterId);
                ins.executeUpdate();
            }
            statusMessage = "Friend accepted!";
            loadFriends();
            loadPendingRequests();
        } catch (SQLException e) {
            statusMessage = e.getMessage();
        }
    }

    public int getFriendIdInput() { return friendIdInput; }
    public void setFriendIdInput(int friendIdInput) { this.friendIdInput = friendIdInput; }
    public String getStatusMessage() { return statusMessage; }
    public List<FriendEntry> getFriendList() { return friendList; }
    public List<FriendEntry> getPendingRequests() { return pendingRequests; }
}
