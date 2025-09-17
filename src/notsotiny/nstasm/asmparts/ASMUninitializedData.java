package notsotiny.nstasm.asmparts;

import java.util.Collections;
import java.util.List;

import notsotiny.nstasm.assembly.ASMResolver;
import notsotiny.nstasm.assembly.UnresolvableException;

/**
 * Uninitialized data
 */
public class ASMUninitializedData implements ASMComponent {
    
    private ASMValue size;
    
    // arbitrary number when we don't know what size resolves to
    private static final int LARGE_NUMBER = 100000;
    
    /**
     * bytes bytes of uninitialized data
     * @param bytes
     */
    public ASMUninitializedData(ASMValue bytes) {
        this.size = bytes;
    }

    @Override
    public int getMaxSize() {
        if(this.size instanceof ASMConstant c) {
            return (int) c.getValue();
        } else {
            return LARGE_NUMBER;
        }
    }

    @Override
    public List<Byte> getBytes(ASMResolver resolver) throws UnresolvableException {
        return Collections.nCopies((int) this.size.getValue(resolver), (byte) 0);
    }
    
    @Override
    public String toString() {
        return "resb " + this.size;
    }
    
}
