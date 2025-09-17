package notsotiny.nstasm.asmparts;

import notsotiny.nstasm.ASMUtil;
import notsotiny.nstasm.assembly.ASMResolver;

/**
 * A constant
 */
public class ASMConstant implements ASMValue {
    
    public static final ASMConstant ZERO = new ASMConstant(0),
                                    ONE = new ASMConstant(1);
    
    private long value;
    
    /**
     * @param value
     */
    public ASMConstant(long value) {
        this.value = value;
    }
    
    /**
     * It's a constant. No resolver needed.
     * @return
     */
    public long getValue() {
        return this.value;
    }

    @Override
    public long getValue(ASMResolver resolver) {
        return this.value;
    }
    
    @Override
    public boolean equals(Object o) {
        if(o instanceof ASMConstant c) {
            return this.value == c.value;
        } else {
            return false;
        }
    }
    
    @Override
    public String toString() {
        return this.toString(ASMUtil.getWidth(this.value & 0x0FFFFFFFFl, false, false, true, true));
    }
    
    /**
     * toString with a specific bit width
     * @param bytes
     * @return
     */
    public String toString(int bytes) {
        return String.format("0x%0" + (2 * bytes) + "X", this.value & 0x0FFFFFFFFl);
    }
    
}
