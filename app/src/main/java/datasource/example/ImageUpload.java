package datasource.example;

import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Named;
import jakarta.servlet.http.Part;

import java.io.*;
import java.nio.file.Files;

@Named("imageUploadBean")
@SessionScoped
public class ImageUpload implements Serializable {

    private static final long serialVersionUID = 1L;
    private transient Part file;
    private byte[] imageData;
    private String imageMimeType;

    public Part getFile() { return file; }
    public void setFile(Part file) { this.file = file; }

    public byte[] getImageData() { return imageData; }
    public String getImageMimeType() { return imageMimeType; }

    public void upload() {
        if (file == null) {
            imageData = null;
            imageMimeType = null;
            return;
        }

        try {
            // Read image bytes
            imageData = file.getInputStream().readAllBytes();
            
            // Get MIME type
            imageMimeType = file.getContentType();
            if (imageMimeType == null) {
                imageMimeType = "application/octet-stream";
            }

            file = null; // clear after upload

        } catch (Exception e) {
            imageData = null;
            imageMimeType = null;
            e.printStackTrace();
        }
    }
}