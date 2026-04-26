package datasource.example;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import javax.naming.Context;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Named;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Name this bean "userLoginBean"
// Make this a SessionScoped bean
@Named("userLoginBean")
@SessionScoped
public class UserLogin implements Serializable{
    @NotNull
    private String userName;
    @NotNull
    @NotBlank
    @Size(min=12,max=36)
    private String userPassword;
    private String token;
    private String message;
    private int userId;
    private Connection conn;

    private boolean verifyPassword(byte[] bytes) throws UnsupportedEncodingException{
        return BCrypt.verifyer().verify(userPassword.getBytes("UTF-16"), bytes).verified;
    }


    // Have this method execute immediately after bean construction using annotations
    @PostConstruct
    public void openConnection(){
        // In a try block
        try {
            // set token to null
            token = null;
            // create a new InitialContext
            Context ctx = new InitialContext();
            // Get the DataSource by lookup with jdbc/Assignment2 under the /comp/env/ in the context
            DataSource ds = (DataSource) ctx.lookup("java:/comp/env/jdbc/FinalJava");
            // Use getConnection on the datasource to assign the conn field
            conn = ds.getConnection();
        } catch (NamingException | SQLException e) {
            // print the exceptions message to System.out
            System.out.println(e.getMessage());
        }
    }

    // Have this method execute immediately before bean destruction using annotations
    @PreDestroy
    public void closeConnection(){
        // If conn isn't null
        if (conn != null) {
            // in a try-block
            try {
                // close conn
                conn.close();
            // Catch SQL Exceptions
            } catch (SQLException e) {
                // print the exception's message to System.out
                System.out.println(e.getMessage());
            }
        }     
    }

     public void updateToken() {
        // In a try-with-resources block
        try (
            // Create the following resource:
            // 1. A PreparedStatement that selects "userID", "token", and "PW_Hash" from the users table, where;
            //                                           "username" equals a Parameter Marker
            PreparedStatement stmt = conn.prepareStatement("SELECT userID, token, PW_Hash FROM users WHERE username = ?");
        ) {
            // In the body
            // set the "username"'s Parameter Marker to userName
            stmt.setString(1, userName);
            // In a try-with-resources block
            try (
                // Create the following resource:
                // 1. A ResultSet from executing the PreparedStatement's query
                ResultSet rs = stmt.executeQuery();
            ) {
                // if rs.next() is true, get the bytes from PW_Hash and call verifyPassword with them; if that is also true
                if (rs.next() && verifyPassword(rs.getBytes(3))) {
                    // set token to the token from the ResultSet
                    token = rs.getString(2);
                    // set userID to the userID from the ResultSet
                    userId = rs.getInt(1);
                    // set message to the empty string
                    message = "";
                // if either condition above was false
                } else {
                    // set message to "Invalid login"
                    message = "Invalid login";
                }
            // Catch SQL and UnsupportedEncoding Exceptions
            } catch (SQLException | UnsupportedEncodingException e) {
                // set message to the exception's message
                message = e.getMessage();
                // return
                return;
            }
        // Catch SQL Exceptions
        } catch (SQLException e) {
            // set message to the exception's message
            message = e.getMessage();
            // return
            return;
        }
    }


    public void signup() {
        // In a try-with-resources resource block
        try (
            // Create the following resource:
            // 1. A PreparedStatement that inserts into the users table,
            //                  the columns "username", "PW_Hash" and "token"
            //                  using Parameter Markers for "username" and "PW_Hash" values, and;
            //                  using SHA2(RAND(), 256) for "token"'s value
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO users (username, PW_Hash, token) VALUES (?, ?, SHA2(RAND(), 256))");
        ) {
            // ensure the following line is inside the try body
            byte[] hash = BCrypt.withDefaults().hash(12, userPassword.getBytes("UTF-16"));
            // set the "username" Parameter marker to userName
            stmt.setString(1, userName);
            // set the "PW_Hash" Parameter marker to hash
            stmt.setBytes(2, hash);
            // execute the statement as an update and store the number of affected rows in an integer variable
            int affectedRows = stmt.executeUpdate();
            // If the integer variable is not 1
            if (affectedRows != 1) {
                // set message to "Failed to create new user: <username>" with <username> replaced with userName's value
                message = "Failed to create new user: " + userName;
            // else
            } else {
                // set message to "Successfully created new user: <username>" with <username> replaced with userName's value
                message = "Successfully created new user: " + userName;
            }
        // Catch SQL and UnsupportedEncoding Exceptions
        } catch (SQLException | UnsupportedEncodingException e) {
            // set message to the exception's message
            message = e.getMessage();
            // return
            return;
        }
    }

    public String getUserName() {
        return userName;
    }
    public void setUserName(String userName) {
        this.userName = userName;
    }
    public String getUserPassword() {
        return userPassword;
    }
    public void setUserPassword(String userPassword) {
        this.userPassword = userPassword;
    }
    public String getToken() {
        return token;
    }
    public int getUserId() {
        return userId;
    }
    public void setUserId(int userId) {
        this.userId = userId;
    }
    public String getMessage() {
        return message;
    }
}
