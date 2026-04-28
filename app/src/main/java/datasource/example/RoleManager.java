package datasource.example;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.io.Serializable;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import java.sql.ResultSet;
import javax.sql.DataSource;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.naming.Context;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named("roleManagerBean")
@SessionScoped
public class RoleManager implements Serializable {
    private Connection conn;

    @Inject
    private ServerView serverView;

    private List<String> roles = new LinkedList<>();
    private List<String> userRoles = new LinkedList<>();
    private String selectedUserName;
    private int currentUserId = 0;
    private int currentRoleId = 0;
    private String newRole = "";
    private String statusMessage = "";

    @PostConstruct
    public void openConnection() {
        try {
            Context ctx = new InitialContext();
            DataSource ds = (DataSource) ctx.lookup("java:comp/env/jdbc/FinalJava");
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

    private int findUserIdByUserName() {
        try(PreparedStatement stmt = conn.prepareStatement("SELECT userID FROM users WHERE userName = ?;");){
            stmt.setString(1, getSelectedUserName());
            ResultSet rs = stmt.executeQuery();
            if(rs.next()){
                return rs.getInt("userID");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public void loadRolesForServer() {
        roles.clear();
        try(PreparedStatement stmt = conn.prepareStatement(
                "SELECT roleID, name FROM roles WHERE serverID = ?");){
            stmt.setInt(1, getCurrentServerId());
            ResultSet rs = stmt.executeQuery();
            while(rs.next()){
                int eachRowId = rs.getInt(1);
                String eachRowName = rs.getString(2);
                roles.add(eachRowId + " - " + eachRowName);
            }
            System.out.println(roles);
        } catch (SQLException e){
            e.printStackTrace();
            
        }

    }

    public void createRole() {
        if (newRole == null || newRole.equals("")) {
            System.out.println("New role cannot be empty");
            return;
        }
        try (
            PreparedStatement selectStmt = conn.prepareStatement(
                "SELECT * FROM roles WHERE name = ? AND serverID = ?");
        ) {
            selectStmt.setString(1, getNewRole());
            selectStmt.setInt(2, getCurrentServerId());
            ResultSet rs = selectStmt.executeQuery();
            if (rs.next()) {
                System.out.println("role already exists.");
                return;
            } else {
                try (
                    PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO roles (serverID, name) VALUES (?,?);",
                    PreparedStatement.RETURN_GENERATED_KEYS);
                ){
                    insertStmt.setInt(1, getCurrentServerId());
                    insertStmt.setString(2, getNewRole());
                    insertStmt.executeUpdate();
                    try (ResultSet keys = insertStmt.getGeneratedKeys()) {
                        if (keys.next()) {
                            int newRoleId = keys.getInt(1);
                            try (PreparedStatement permStmt = conn.prepareStatement(
                                "INSERT INTO channel_role_perms (channelID, roleID, canRead, canWrite) " +
                                "SELECT channelID, ?, true, true FROM channels WHERE serverID = ?")) {
                                permStmt.setInt(1, newRoleId);
                                permStmt.setInt(2, getCurrentServerId());
                                permStmt.executeUpdate();
                            }
                        }
                    }
                    
                    loadRolesForServer();
                    newRole = "";
                    System.out.println("new role created successfully!");
                    statusMessage = "new role created successfully!";
                    } catch (SQLException e){
                    System.out.println("could not create new role");
                    e.printStackTrace();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void assignRoleToUser() {
    int userID = findUserIdByUserName();
    if(userID!=0){
        if(getCurrentRoleId()!=0){
            try(PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT * FROM user_roles WHERE userID = ? AND roleID = ?;");
                ){
                    selectStmt.setInt(1, userID);
                    selectStmt.setInt(2, getCurrentRoleId());
                    ResultSet rs = selectStmt.executeQuery();
                    if(rs.next()){
                        System.out.println("role already exists for user.");
                        return;
                    } else {
                        try(PreparedStatement insertStmt = conn.prepareStatement(
                            "INSERT INTO user_roles (userID, roleID) VALUES (?,?);"
                        );) {
                            insertStmt.setInt(1, userID);
                            insertStmt.setInt(2, getCurrentRoleId());
                            insertStmt.executeUpdate();
                            try(PreparedStatement deletePermsStmt = conn.prepareStatement(
                                "DELETE FROM channel_member_perms WHERE userID = ? AND channelID IN " +
                                "(SELECT channelID FROM channels WHERE serverID = (SELECT serverID FROM roles WHERE roleID = ?))"
                            );) {
                                deletePermsStmt.setInt(1, getCurrentUserId());
                                deletePermsStmt.setInt(2, getCurrentRoleId());
                                deletePermsStmt.executeUpdate();
                            }
                            
                            System.out.println("User's role added successfully!");
                            statusMessage = "User's role added successfully!";
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                    }

                } catch (SQLException e){
                    e.printStackTrace();
                }
        } else {
            System.out.println("Must be a valid role ID. Please try again.");
        }
    } else {
        System.out.println("Must be a valid username. Please try again.");
        return;
    }
    }

    public void deleteUserRole() {
        int userID = findUserIdByUserName();
        if(userID!=0){
            if(getCurrentRoleId()!=0){
                try(PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM user_roles WHERE userID = ? AND roleID = ?;");
                ){
                    stmt.setInt(1, userID);
                    stmt.setInt(2, getCurrentRoleId());
                    int rolesDeleted = stmt.executeUpdate();
                    if(rolesDeleted > 0) {
                        try(PreparedStatement restorePermsStmt = conn.prepareStatement(
                            "INSERT INTO channel_member_perms (channelID, userID, canRead, canWrite) " +
                            "SELECT c.channelID, ?, true, true FROM channels c " +
                            "WHERE c.serverID = (SELECT serverID FROM roles WHERE roleID = ?) " +
                            "AND NOT EXISTS (SELECT 1 FROM channel_member_perms cmp WHERE cmp.channelID = c.channelID AND cmp.userID = ?)"
                        );) {
                            restorePermsStmt.setInt(1, getCurrentUserId());
                            restorePermsStmt.setInt(2, getCurrentRoleId());
                            restorePermsStmt.setInt(3, getCurrentUserId());
                            restorePermsStmt.executeUpdate();
                        }
                        System.out.println("User's role deleted successfully!");
                        statusMessage = "User's role deleted successfully!";
                        return;
                    } else {
                        System.out.println("User had never been assigned that role.");
                        return;
                    }
                } catch (SQLException e){
                    e.printStackTrace();
                }
            } else {
                System.out.println("Must be a valid role ID. Please try again.");
                return;
            }
        } else {
            System.out.println("Must be a valid username. Please try again.");
            return;
     }
    }

    public void loadUserRoles() {
        userRoles.clear();
        int userID = findUserIdByUserName();
        if (userID == 0){
            System.out.println("Must be a valid username. Please try again.");
           return;
        }
        try(
            PreparedStatement userStmt = conn.prepareStatement(
                "SELECT roleID from user_roles WHERE userID = ?;");
        ){ 
            userStmt.setInt(1, userID);
            ResultSet rs = userStmt.executeQuery();
            while (rs.next()){
                int rsRoleId = rs.getInt("roleID");
                try(
                    PreparedStatement roleStmt = conn.prepareStatement(
                        "SELECT roleID, name FROM roles WHERE roleID = ?;"
                    );){
                        roleStmt.setInt(1, rsRoleId);
                        ResultSet rs2 = roleStmt.executeQuery();
                        if(rs2.next()){
                            String roleName = rs2.getString("name");
                            int roleID = rs2.getInt("roleID");
                            userRoles.add(roleID + " - " + roleName );
                        }
                    }
            }
            System.out.println(userRoles);
        } catch (SQLException e){
            e.printStackTrace();
        }
    }

    public String getSelectedUserName() {
        return selectedUserName;
    }

    public void setSelectedUserName(String selectedUserName) {
        this.selectedUserName = selectedUserName;
    }

    public List<String> getRoles() {
        return roles;
    }

    public List<String> getUserRoles() {
        return userRoles;
    }

    public int getCurrentServerId() {
        return serverView.getCurrentServerId();
    }

    public int getCurrentUserId() {
        return currentUserId;
    }

    public int getCurrentRoleId() {
        return currentRoleId;
    }

    public String getNewRole() {
        return newRole;
    }

    public void setNewRole(String newRole) {
        this.newRole = newRole;
    }

    public void setCurrentRoleId(int currentRoleId) {
        this.currentRoleId = currentRoleId;
    }

    public void setCurrentUserId(int currentUserId) {
        this.currentUserId = currentUserId;
    }
    
    public String getStatusMessage() {
        return statusMessage;
    }


}