package notsotiny.nstasm.asmparts;

import java.util.ArrayList;
import java.util.List;

import notsotiny.nstasm.assembly.ASMResolver;
import notsotiny.nstasm.assembly.UnresolvableException;

/**
 * An ASMComponent repeated n times, macro-style (e.g. jump offsets will be different for each copy)
 */
public class ASMRepetition implements ASMComponent {
    
    private List<ASMComponent> components;
    
    private ASMValue n;
    
    // arbitrary number when we don't know what n resolves to
    private static final int LARGE_NUMBER = 100000;
    
    /**
     * component duplicated n times
     * @param component
     * @param n
     */
    public ASMRepetition(List<ASMComponent> components, ASMValue n) {
        this.components = components;
        this.n = n;
    }
    
    /**
     * Gets the repeated components
     * @return
     */
    public List<ASMComponent> getRepeatedComponents() {
        return this.components;
    }
    
    /**
     * Gets the number of repetitions
     * @return
     */
    public ASMValue getNumRepetitions() {
        return this.n;
    }
    
    /**
     * Gets all repeated components
     * @param resolver
     * @return
     * @throws UnresolvableException 
     */
    public List<ASMComponent> getAllComponents(ASMResolver resolver) throws UnresolvableException {
        int reps = (int) this.n.getValue(resolver);
        
        // clamp to nonnegative
        reps = reps < 0 ? 0 : reps;
        
        List<ASMComponent> allComponents = new ArrayList<>(reps * this.components.size());
        
        for(int i = 0; i < reps; i++) {
            allComponents.addAll(this.components);
        }
        
        return allComponents;
    }

    @Override
    public int getMaxSize() {
        int size = 0;
        
        for(ASMComponent c : components) {
            size += c.getMaxSize();
        }
        
        if(this.n instanceof ASMConstant c) {
            return size * (int) c.getValue();
        } else {
            return size * LARGE_NUMBER;
        }
    }
    
    @Override
    public List<Byte> getBytes(ASMResolver resolver) {
        // To handle special symbols correctly, expansion is done by the resolver
        throw new UnsupportedOperationException();
    }
    
    @Override
    public String toString() {
        return "repeat " + this.n + ", " + this.components;
    }
    
}
