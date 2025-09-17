package notsotiny.nstasm.asmparts;

import notsotiny.sim.Register;

/**
 * A memory accessor
 */
public class ASMMemory implements ASMLocation {
    
    private Register base, index;
    
    private ASMValue scale, offset;
    
    /**
     * Full constructor
     * @param base
     * @param index
     * @param scale
     * @param offset
     */
    public ASMMemory(Register base, Register index, ASMValue scale, ASMValue offset) {
        this.base = base == null ? Register.NONE : base;
        this.index = index == null ? Register.NONE : index;
        this.scale = scale;
        this.offset = offset;
    }
    
    public Register getBase() { return this.base; }
    public Register getIndex() { return this.index; }
    public ASMValue getScale() { return this.scale; }
    public ASMValue getOffset() { return this.offset; }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        
        if(this.base != Register.NONE) {
            sb.append(this.base);
            
            if(this.index != Register.NONE) {
                sb.append(" + ");
                
                if(this.scale != null) {
                    sb.append(this.scale);
                    sb.append("*");
                }
                
                sb.append(this.index);
            }
            
            if(this.offset != null) {
                sb.append(" + ");
                sb.append(this.offset);
            }
        } else {
            if(this.index != Register.NONE) {
                if(this.scale != null) {
                    sb.append(this.scale);
                    sb.append("*");
                }
                
                sb.append(this.index);
                
                if(this.offset != null) {
                    sb.append(" + ");
                    sb.append(this.offset);
                }
            } else {
                sb.append(this.offset);
            }
        }
        
        sb.append("]");
        return sb.toString();
    }
    
}
