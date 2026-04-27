package datasource.example;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@WebServlet("/serveImage")
public class ServeImage extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String messageId = req.getParameter("messageId");
        
        if (messageId == null || messageId.isEmpty()) {
            resp.sendError(400, "Missing messageId parameter");
            return;
        }

        try {
            // Get database connection
            Context ctx = new InitialContext();
            DataSource ds = (DataSource) ctx.lookup("java:/comp/env/jdbc/FinalJava");
            
            try (Connection conn = ds.getConnection()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT imageData, imageMimeType FROM messages WHERE messageID = ?")) {
                    
                    stmt.setInt(1, Integer.parseInt(messageId));
                    ResultSet rs = stmt.executeQuery();
                    
                    if (rs.next()) {
                        byte[] imageData = rs.getBytes("imageData");
                        String mimeType = rs.getString("imageMimeType");
                        
                        if (imageData != null && imageData.length > 0) {
                            resp.setContentType(mimeType != null ? mimeType : "image/jpeg");
                            resp.setContentLength(imageData.length);
                            resp.getOutputStream().write(imageData);
                            resp.getOutputStream().flush();
                            return;
                        }
                    }
                    
                    resp.sendError(404, "Image not found");
                }
            }
        } catch (NamingException | SQLException | NumberFormatException e) {
            resp.sendError(500, "Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
