package notsotiny.nstasm.asmparts;

import java.util.List;

import notsotiny.nstasm.assembly.ASMResolver;

/**
 * A label
 */
public class ASMLabel implements ASMComponent {
    
    private String name;
    
    /**
     * @param name
     */
    public ASMLabel(String name) {
        this.name = name;
    }
    
    /**
     * @return Label name
     */
    public String getName() {
        return this.name;
    }

    @Override
    public int getMaxSize() {
        return 0;
    }
    
    @Override
    public List<Byte> getBytes(ASMResolver resolver) {
        return List.of();
    }
    
    @Override
    public String toString() {
        return this.name;
    }
    
}
