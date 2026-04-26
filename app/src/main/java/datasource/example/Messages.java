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

// Name this bean "chatLogBean"
// Make this a SessionScoped bean
@Named("chatLogBean")
@SessionScoped
public class Messages implements Serializable{
    private static final long serialVersionUID = 1L;
    private List<String> chatLog = new LinkedList<>();
    private transient Connection conn;
    private String messageToPost;
    @Inject private UserLogin login;

    // Have this method execute immediately after bean construction using annotations
    @PostConstruct
    public void openConnection(){
        // In a try block
        try {
            // create a new InitialContext
            Context ctx = new InitialContext();
            // Get the DataSource by lookup with jdbc/FinalJava under the /comp/env/ in the context
            DataSource ds = (DataSource)ctx.lookup("java:/comp/env/jdbc/FinalJava");
            // Use getConnection on the datasource to assign the conn field
            this.conn = ds.getConnection();
        // catch Naming and SQL exception's
        } catch (NamingException | SQLException e) {
            // print the exceptions message to System.out
            System.out.println(e.getMessage());
        }
    }

    // Have this method execute immediately before bean destruction using annotations
    @PreDestroy
    public void closeConnection(){
        // If conn isn't null
        if (this.conn != null) {
            // in a try-block
            try {
                // close conn
                this.conn.close();
            // Catch SQL Exceptions
            } catch (SQLException e) {
                // print the exception's message to System.out
                System.out.println(e.getMessage());
            }
        }           
    }

    private void ensureConnection() {
        if (this.conn == null) {
            try {
                Context ctx = new InitialContext();
                DataSource ds = (DataSource)ctx.lookup("java:/comp/env/jdbc/FinalJava");
                this.conn = ds.getConnection();
            } catch (NamingException | SQLException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    public void pullLog() {
        ensureConnection();
        // In a try-with-resources resource block
        try (
            // Create the following resources:
            // 1. A PreparedStatement that selects "message" and "sentOn" from the messages table, and;
            //                                           "username" from the users table by;
            //                                           joining the users and messages table on the "userID" columns in both tables
            PreparedStatement stmt = conn.prepareStatement("SELECT m.message, m.sentOn, u.username" +
                " FROM messages m" +
                " JOIN users u ON m.userID = u.userID");
            // 2. A ResultSet by executing the PreparedStatement's query
            ResultSet rs = stmt.executeQuery();
        ) {
            // In the body
            // clear the chatLog
            chatLog.clear();
            // while the ResultSet has more entries
            while (rs.next()) {
                // Create a string that looks like "<username>@<sentOn>: <message>" where:
                //          <username> is the username String for this row in the ResultSet
                //          <sentOn> is the sentOn Timestamp for this row in the ResultSet
                //          <message> is the message String for this row in the ResultSet
                StringBuilder sb = new StringBuilder();
                sb.append(rs.getString(3)).append("@").append(rs.getTimestamp(2)).append(": ").append(rs.getString(1));
                // Add that String to the chatLog
                chatLog.add(sb.toString());
            }
        // Catch SQL Exceptions
        } catch (SQLException e) {
            // print the exception's message to System.out
            System.out.println(e.getMessage());
        }
    }

    public void postMessage(){
        ensureConnection();
        // In a try-with-resources resource block
        try (
            // Create the following resource:
            // 1. A PreparedStatement that inserts into the messages table,
            //                  the columns "message", "sentOn" and "userID"
            //                  using Parameter Markers for all 3 values
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO messages (message, sentOn, userID) VALUES (?, ?, ?)");
        ) {
            // In the body
            // set "message"'s Placemarker to messageToPost
            // set "sentOn"'s Placemarker to new Timestamp(Instant.now().toEpochMilli())
            // set "userID"'s Placemarker to login.getUserId()
            // execute the statement
            stmt.setString(1, messageToPost);
            stmt.setTimestamp(2, new Timestamp(Instant.now().toEpochMilli()));
            stmt.setInt(3, login.getUserId());
            stmt.executeUpdate();
        // Catch SQL Exceptions
        } catch (SQLException e) {
            // print the exception's message to System.out
            System.out.println(e.getMessage());
        }
        // call pullLog();
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
