package notsotiny.nstasm.asmparts;

import java.util.ArrayList;
import java.util.List;

import notsotiny.nstasm.ASMUtil;
import notsotiny.nstasm.asmparts.ASMReference.ReferenceType;
import notsotiny.nstasm.assembly.ASMResolver;
import notsotiny.nstasm.assembly.UnresolvableException;

/**
 * Initialized data
 */
public class ASMInitializedData implements ASMComponent {

    private int dataSize;
    
    private List<ASMValue> values;
    
    public ASMInitializedData(List<ASMValue> values, int dataSize) {
        this.dataSize = dataSize;
        this.values = values;
    }
    
    public int getDataSize() { return this.dataSize; }
    public List<ASMValue> getValues() { return this.values; }
    
    @Override
    public int getMaxSize() {
        return this.dataSize * this.values.size();
    }

    @Override
    public List<Byte> getBytes(ASMResolver resolver) throws UnresolvableException {
        List<Byte> bytes = new ArrayList<>(this.values.size());
        
        // Process each value
        for(ASMValue v : values) {
            // Add bytes in little-endian order
            long lv;
            
            if(v instanceof ASMReference ref) {
                // Unresolved reference allowed
                if(ref.getType() == ReferenceType.NORMAL) { 
                    lv = 0;
                    resolver.placeReference(ref.getName(), 0);
                } else {
                    lv = v.getValue(resolver);
                }
            } else {
                lv = v.getValue(resolver);
            }
            
            ASMUtil.addBytes(bytes, lv, this.dataSize);
        }
        
        return bytes;
    }
    
    @Override
    public String toString() {
        return switch(this.dataSize) {
            case 4  -> "dp";
            case 2  -> "dw";
            default -> "db";
        } + this.values;
    }
    
}
