package notsotiny.nstasm.assembly;

import notsotiny.nstasm.ASMUtil;

/**
 * Represents an immediate
 */
public record ResolvedImmediate(boolean resolved, long value) {
    
    @Override
    public String toString() {
        if(this.resolved) {
            return String.format("0x%0" + (2 * ASMUtil.getWidth(this.value, false, false, true, true)) + "X", this.value);
        } else {
            return "UNRESOLVED";
        }
    }
    
}
