package notsotiny.nstasm.asmparts;

import java.util.List;

import notsotiny.nstasm.assembly.ASMResolver;
import notsotiny.nstasm.assembly.UnresolvableException;

/**
 * An assembly component.
 */
public interface ASMComponent {
    
    /**
     * Determines the maximum size of this component
     * @return Maximum size in bytes
     */
    public int getMaxSize();
    
    /**
     * Gets the bytes of this component
     * @param resolver
     * @return
     * @throws UnresolvableException 
     */
    public List<Byte> getBytes(ASMResolver resolver) throws UnresolvableException;
    
}
