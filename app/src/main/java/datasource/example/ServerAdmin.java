package datasource.example;

import jakarta.inject.Inject;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
import jakarta.inject.Named;
import java.sql.ResultSet;

@Named("serverAdminBean")
@SessionScoped
public class ServerAdmin implements Serializable {
    private Connection conn;

    @Inject
    private ServerView serverView;
    private int selectedUserId = 0;
    private List<String> permissions = new LinkedList<>();
    private String selectedPermission;
    private List<String> serverMembers = new LinkedList<>();
    
    
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

    public void loadServerMembers() {
        serverMembers.clear();
        try(PreparedStatement stmt = conn.prepareStatement(
                "SELECT userID FROM server_members WHERE serverID = ?;");){
            stmt.setInt(1, getCurrentServerId());
            ResultSet rs = stmt.executeQuery();
            while(rs.next()){
                int eachUserId = rs.getInt(1);
                try(PreparedStatement stmt2 = conn.prepareStatement("SELECT userName FROM users WHERE userID =?;");){
                    stmt2.setInt(1, eachUserId);
                    ResultSet rs2 = stmt2.executeQuery();
                    if(rs2.next()){
                            String usersName = rs2.getString("userName");
                            serverMembers.add(usersName);
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
                System.out.println(serverMembers);
        } catch (SQLException e){
            e.printStackTrace();
        }
    }

    public void kickUser() {
         if(getSelectedUserId()!=0){
            try(PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT * FROM server_members WHERE userID = ? AND serverID = ?;");
                ){
                    selectStmt.setInt(1, getSelectedUserId());
                    selectStmt.setInt(2, getCurrentServerId());
                    ResultSet rs = selectStmt.executeQuery();
                    if(!rs.next()){
                        System.out.println("No member of this ID in this server.");
                        return;
                    } else {
                        try(PreparedStatement deleteStmt = conn.prepareStatement(
                            "DELETE FROM server_members WHERE userID = ? AND serverID = ?;"
                        );) {
                            deleteStmt.setInt(1, getSelectedUserId());
                            deleteStmt.setInt(2, getCurrentServerId());
                            deleteStmt.executeUpdate();
                            System.out.println("Member deleted from server successfully!");
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                    }
                } catch (SQLException e) {
                    e.printStackTrace();    
                }
            }
        }

    public void grantPermissions() {
        if(getSelectedUserId()!=0){
            try(PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT * FROM server_members WHERE userID = ? AND serverID = ?;");
                ){
                    selectStmt.setInt(1, getSelectedUserId());
                    selectStmt.setInt(2, getCurrentServerId());
                    ResultSet rs = selectStmt.executeQuery();
                    if(rs.next()){
                        String requestedPermission = getSelectedPermission();
                        if (requestedPermission != null){
                            boolean canInvite = false;
                            boolean canCreateChannels = false;
                            boolean canKick = false;
                            if(requestedPermission.equalsIgnoreCase("invite")){
                                canInvite = true;
                            }
                            else if (requestedPermission.equalsIgnoreCase("create channels")){
                                canCreateChannels = true;
                            }
                            else if (requestedPermission.equalsIgnoreCase("kick")){
                                canKick = true;
                            }
                            else {
                                System.out.println("invalid member permission. Please choose from one of the following: invite, create channels, or kick.");
                                return; 
                            }
                            try(PreparedStatement selectStmt2 = conn.prepareStatement(
                            "SELECT * FROM member_permissions WHERE userID = ? AND serverID = ?;");
                            ){
                                selectStmt2.setInt(1, getSelectedUserId());
                                selectStmt2.setInt(2, getCurrentServerId());
                                ResultSet rs2 = selectStmt2.executeQuery();
                                if(!rs2.next()){
                                    try(PreparedStatement insertStmt = conn.prepareStatement(
                                    "INSERT INTO member_permissions (userID, serverID, canInvite, canKick, canCreateChannels) VALUES (?,?,?,?,?);");
                                    )  {
                                        insertStmt.setInt(1, getSelectedUserId());
                                        insertStmt.setInt(2, getCurrentServerId());
                                        insertStmt.setBoolean(3, canInvite);
                                        insertStmt.setBoolean(4, canKick);
                                        insertStmt.setBoolean(5, canCreateChannels);
                                        insertStmt.executeUpdate();
                                        System.out.println("Permissions updated successfully!");
                                    } catch (SQLException e) {
                                        e.printStackTrace();
                                    }   
                                } else {
                                    if (canInvite){
                                        try(PreparedStatement updateStmtInv = conn.prepareStatement(
                                        "UPDATE member_permissions SET canInvite = true WHERE userID = ? AND serverID = ?;");)
                                        {
                                            updateStmtInv.setInt(1, getSelectedUserId());
                                            updateStmtInv.setInt(2, getCurrentServerId());
                                            updateStmtInv.executeUpdate();
                                            System.out.println("Permission updated successfully!");
                                        } catch (SQLException e) {
                                            e.printStackTrace();
                                        }
                                    } else if (canCreateChannels) {
                                        try(PreparedStatement updateStmtCC = conn.prepareStatement(
                                        "UPDATE member_permissions SET canCreateChannels = true WHERE userID = ? AND serverID = ?;");)
                                        { 
                                            updateStmtCC.setInt(1, getSelectedUserId());
                                            updateStmtCC.setInt(2, getCurrentServerId());
                                            updateStmtCC.executeUpdate();
                                            System.out.println("Permission updated successfully!");
                                        } catch (SQLException e) {
                                            e.printStackTrace();
                                        }
                                    } else if (canKick) {
                                        try(PreparedStatement updateStmtkick= conn.prepareStatement(
                                        "UPDATE member_permissions SET canKick = true WHERE userID = ? AND serverID = ?;");)
                                        { 
                                            updateStmtkick.setInt(1, getSelectedUserId());
                                            updateStmtkick.setInt(2, getCurrentServerId());
                                            updateStmtkick.executeUpdate();
                                            System.out.println("Permission updated successfully!");
                                        } catch (SQLException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            } catch (SQLException e){
                                e.printStackTrace();
                        }

                    } else {
                        System.out.println("No member of this ID in this server.");
                        return;
                    }
                }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
        } 
    }

    public void revokePermissions() {
        if(getSelectedUserId()!=0){
            try(PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT * FROM server_members WHERE userID = ? AND serverID = ?;");
                ){
                    selectStmt.setInt(1, getSelectedUserId());
                    selectStmt.setInt(2, getCurrentServerId());
                    ResultSet rs = selectStmt.executeQuery();
                    if(rs.next()){
                        String requestedPermission = getSelectedPermission();
                        if (requestedPermission != null){
                            boolean canInvite = false;
                            boolean canCreateChannels = false;
                            boolean canKick = false;
                            if(requestedPermission.equalsIgnoreCase("invite")){
                                canInvite = true;
                            }
                            else if (requestedPermission.equalsIgnoreCase("create channels")){
                                canCreateChannels = true;
                            }
                            else if (requestedPermission.equalsIgnoreCase("kick")){
                                canKick = true;
                            }
                            else {
                                System.out.println("invalid member permission. Please choose from one of the following: invite, create channels, or kick.");
                                return; 
                            }
                            try(PreparedStatement selectStmt2 = conn.prepareStatement(
                            "SELECT * FROM member_permissions WHERE userID = ? AND serverID = ?;");
                            ){
                                selectStmt2.setInt(1, getSelectedUserId());
                                selectStmt2.setInt(2, getCurrentServerId());
                                ResultSet rs2 = selectStmt2.executeQuery();
                                if(!rs2.next()){
                                    System.out.println("No permission to revoke.");
                                    return;
                                } else {
                                    if (canInvite){
                                        try(PreparedStatement updateStmtInv = conn.prepareStatement(
                                        "UPDATE member_permissions SET canInvite = false WHERE userID = ? AND serverID = ?;");)
                                        {
                                            updateStmtInv.setInt(1, getSelectedUserId());
                                            updateStmtInv.setInt(2, getCurrentServerId());
                                            updateStmtInv.executeUpdate();
                                            System.out.println("Permission revoked successfully!");
                                        } catch (SQLException e) {
                                            e.printStackTrace();
                                        }
                                    } else if (canCreateChannels) {
                                        try(PreparedStatement updateStmtCC = conn.prepareStatement(
                                        "UPDATE member_permissions SET canCreateChannels = false WHERE userID = ? AND serverID = ?;");)
                                        { 
                                            updateStmtCC.setInt(1, getSelectedUserId());
                                            updateStmtCC.setInt(2, getCurrentServerId());
                                            updateStmtCC.executeUpdate();
                                            System.out.println("Permission revoked successfully!");
                                        } catch (SQLException e) {
                                            e.printStackTrace();
                                        }
                                    } else if (canKick) {
                                        try(PreparedStatement updateStmtkick= conn.prepareStatement(
                                        "UPDATE member_permissions SET canKick = false WHERE userID = ? AND serverID = ?;");)
                                        { 
                                            updateStmtkick.setInt(1, getSelectedUserId());
                                            updateStmtkick.setInt(2, getCurrentServerId());
                                            updateStmtkick.executeUpdate();
                                            System.out.println("Permission revoked successfully!");
                                        } catch (SQLException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            } catch (SQLException e){
                                e.printStackTrace();
                        }

                    } else {
                        System.out.println("No member of this ID in this server.");
                        return;
                    }
                }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
        } 
    }
    
    public int getCurrentServerId() {
        return serverView.getCurrentServerId();
    }
    
    public int getSelectedUserId() {
        return selectedUserId;
    }

    public void setSelectedUserId(int selectedUserId) {
        this.selectedUserId = selectedUserId;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }

    public String getSelectedPermission() {
        return selectedPermission;
    }

    public void setSelectedPermission(String selectedPermission) {
        this.selectedPermission = selectedPermission;
    }

    public List<String> getServerMembers() {
        return serverMembers;
    }

    public void setServerMembers(List<String> serverMembers) {
        this.serverMembers = serverMembers;
    }

}