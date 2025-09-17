package notsotiny.nstasm;

/**
 * An exception indicating an issue during assembly
 */
public class AssemblyException extends Exception {

    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    
    public AssemblyException() {
        super("");
    }
    
    public AssemblyException(String message) {
        super(message);
    }
    
}
