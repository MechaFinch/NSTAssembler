package notsotiny.nstasm.asmparts;

import notsotiny.nstasm.assembly.ASMResolver;
import notsotiny.nstasm.assembly.UnresolvableException;

/**
 * An integer
 */
public interface ASMValue extends ASMLocation {
    
    /**
     * Gets the resolved integer value of this value
     * @param resolver
     * @return
     * @throws UnresolvableException if the value cannot be resolved
     */
    public long getValue(ASMResolver resolver) throws UnresolvableException;
    
}
