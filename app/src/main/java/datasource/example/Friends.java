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

    // Represents one friend in the friends list (their ID and display name)
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

    // Fetches the current user's accepted friends from the database and stores them for display
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

    // Fetches any friend requests other users have sent to the current user that haven't been accepted yet
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

    // Sends a friend request to another user by ID.
    // If that user already sent you a request, it automatically becomes accepted for both sides.
    public void addFriend() {
        int me = login.getUserId();
        if (friendIdInput == me) {
            statusMessage = "You cannot add yourself as a friend.";
            return;
        }
        try {
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT status FROM friends WHERE userID = ? AND friendID = ?")) {
                check.setInt(1, friendIdInput);
                check.setInt(2, me);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next() && "pending".equals(rs.getString(1))) {
                        // The other user already sent a request, so just accept it for both
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
            // If this user was previously blocked, the block stays — a friend request won't undo it
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

    // Blocks a user by ID. They are also removed from that user's friend list so they can no longer see you.
    public void blockUser() {
        int me = login.getUserId();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO friends (userID, friendID, status) VALUES (?, ?, 'blocked') " +
                "ON DUPLICATE KEY UPDATE status = 'blocked'")) {
            stmt.setInt(1, me);
            stmt.setInt(2, friendIdInput);
            stmt.executeUpdate();
        } catch (SQLException e) {
            statusMessage = e.getMessage();
            return;
        }
        // Remove us from the blocked user's friend list so they can't see us either
        try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM friends WHERE userID = ? AND friendID = ?")) {
            del.setInt(1, friendIdInput);
            del.setInt(2, me);
            del.executeUpdate();
        } catch (SQLException e) {
            statusMessage = e.getMessage();
            return;
        }
        statusMessage = "User " + friendIdInput + " has been blocked.";
        loadFriends();
        loadPendingRequests();
    }

    // Accepts a pending friend request from another user, making the friendship mutual in the database
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
