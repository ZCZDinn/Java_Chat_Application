package datasource.example;

import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Named;
import jakarta.servlet.http.Part;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

@Named("imageUploadBean")
@SessionScoped
public class ImageUpload implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String UPLOAD_DIR = "uploads";
    private transient Part file;

    public Part getFile() { return file; }
    public void setFile(Part file) { this.file = file; }

    public String upload() {
        if (file == null) return null;

        try {
            String fileName = System.currentTimeMillis() + "_" + file.getSubmittedFileName();
            
            // Use system temp or current directory
            String uploadPath = System.getProperty("catalina.base") != null
                ? System.getProperty("catalina.base") + File.separator + "webapps" + File.separator + "app" + File.separator + UPLOAD_DIR
                : UPLOAD_DIR;

            File folder = new File(uploadPath);
            if (!folder.exists()) {
                boolean created = folder.mkdirs();
                if (!created) return null;
            }
            
            File saved = new File(folder, fileName);

            try (InputStream input = file.getInputStream()) {
                Files.copy(input, saved.toPath());
            }

            file = null; // clear after upload

            return UPLOAD_DIR + "/" + fileName;

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}