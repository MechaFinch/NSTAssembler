package notsotiny.nstasm.asmparts;

import notsotiny.nstasm.assembly.ASMResolver;
import notsotiny.nstasm.assembly.UnresolvableException;

/**
 * A reference to a label
 */
public class ASMReference implements ASMValue {
    
    /**
     * Type of a reference.
     * NORMAL = absolute address, can be inferred to be relative for branches
     * RELATIVE_CURRENT = absolute address - IP of current instruction ($)
     * RELATIVE_PREVIOUS = absolute address - IP of previous instruction (@)
     * RELATIVE_START = absolute address - start of current instruction (#)
     */
    public enum ReferenceType {
        NORMAL, RELATIVE_CURRENT, RELATIVE_PREVIOUS, RELATIVE_START
    }
    
    private String name;
    
    private ReferenceType type;
    
    /**
     * A reference of the given type
     * @param name
     * @param type
     */
    public ASMReference(String name, ReferenceType type) {
        this.name = name;
        this.type = type;
    }
    
    public String getName() { return this.name; }
    public ReferenceType getType() { return this.type; }

    @Override
    public long getValue(ASMResolver resolver) throws UnresolvableException {
        if(this.type != ReferenceType.NORMAL) {
            return resolver.getOffset(this);
        } else if(this.name.equals("%%i")) {
            // %%i = repetition number
            return (long) resolver.getInnermostIndex();
        } else {
            throw new UnresolvableException(this.toString());
        }
    }
    
    @Override
    public String toString() {
        return switch(this.type) {
            case RELATIVE_CURRENT   -> "$" + this.name;
            case RELATIVE_PREVIOUS  -> "@" + this.name;
            case RELATIVE_START     -> "#" + this.name;
            default                 -> this.name;
        };
    }
    
}
