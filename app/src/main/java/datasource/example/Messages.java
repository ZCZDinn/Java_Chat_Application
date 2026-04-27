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

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import javax.naming.Context;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

// Make this a SessionScoped bean
@Named("chatLogBean")
@SessionScoped
public class Messages implements Serializable{
    private List<String> chatLog = new LinkedList<>();
    private Connection conn;
    private String messageToPost;
    @Inject private UserLogin login;

    // Opens a connection to the database when this bean is first created for a user's session
    @PostConstruct
    public void openConnection(){
        try {
            Context ctx = new InitialContext();
            DataSource ds = (DataSource)ctx.lookup("java:/comp/env/jdbc/FinalJava");
            this.conn = ds.getConnection();
        } catch (NamingException | SQLException e) {
            System.out.println(e.getMessage());
        }
    }

    // Closes the database connection when the user's session ends
    @PreDestroy
    public void closeConnection(){
        if (this.conn != null) {
            try {
                this.conn.close();
            } catch (SQLException e) {
                System.out.println(e.getMessage());
            }
        }           
    }

    // Fetches all chat messages from the database and refreshes the displayed chat log
    public void pullLog() {
        try (
            PreparedStatement stmt = conn.prepareStatement("SELECT m.message, m.sentOn, u.username" +
                " FROM messages m" +
                " JOIN users u ON m.userID = u.userID");
            ResultSet rs = stmt.executeQuery();
        ) {
            chatLog.clear();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                sb.append(rs.getString(3)).append("@").append(rs.getTimestamp(2)).append(": ").append(rs.getString(1));
                chatLog.add(sb.toString());
            }
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
    }

    // Saves the user's typed message to the database and refreshes the chat log
    public void postMessage(){
        try (
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO messages (message, sentOn, userID) VALUES (?, ?, ?)");
        ) {
            stmt.setString(1, messageToPost);
            stmt.setTimestamp(2, new Timestamp(Instant.now().toEpochMilli()));
            stmt.setInt(3, login.getUserId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
        pullLog();
    }

    public List<String> getChatLog(){
        return chatLog;
    }

    public String getMessageToPost() {
        return messageToPost;
    }

    public void setMessageToPost(String messageToPost) {
        this.messageToPost = messageToPost;
    }
}
