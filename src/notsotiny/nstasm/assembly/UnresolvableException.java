package notsotiny.nstasm.assembly;

/**
 * An exception that indicates that a value cannot be resolved
 */
public class UnresolvableException extends Exception {

    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    
    private final String cause;
    
    public UnresolvableException(String cause) {
        this.cause = cause;
    }
    
    public UnresolvableException() {
        this("");
    }
    
    public String cause() { return this.cause; }
    
}
