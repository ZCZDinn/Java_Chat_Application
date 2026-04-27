package datasource.example;
import java.io.Serializable;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Named;

@Named("currentContext")
@SessionScoped
public class CurrentContext implements Serializable {

    private static final long serialVersionUID = 1L;
    private int currentServerID;

    public int getCurrentServerID() {
        return currentServerID;
    }

    public void setCurrentServerID(int id) {
        this.currentServerID = id;
    }
}