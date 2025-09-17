package notsotiny.nstasm.asmparts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A library/object in assembly form
 */
public class ASMObject {
    
    private String name;
    
    private boolean allowsLengthOptimization,
                    privileged;
    
    private ASMValue origin;
    
    private List<ASMComponent> components;
    
    private Map<Path, String> libraryMap;
    
    /**
     * Assembly object
     * @param name
     * @param components
     * @param libraryMap included library -> local name
     * @param allowsLengthOptimization
     * @param privileged
     * @param origin Origin address or -1
     */
    public ASMObject(String name, List<ASMComponent> components, Map<Path, String> libraryMap, boolean allowsLengthOptimization, boolean privileged, ASMValue origin) {
        this.name = name;
        this.components = components;
        this.libraryMap = libraryMap;
        this.allowsLengthOptimization = allowsLengthOptimization;
        this.privileged = privileged;
        this.origin = origin;
    }
    
    /**
     * Default empty object
     * @param name
     */
    public ASMObject(String name) {
        this(name, new ArrayList<>(), new HashMap<>(), true, false, new ASMConstant(-1));
    }
    
    /**
     * Add a component
     * @param c
     */
    public void addComponent(ASMComponent c) {
        this.components.add(c);
    }
    
    /**
     * Add a list of components
     * @param cs
     */
    public void addComponents(List<ASMComponent> cs) {
        this.components.addAll(cs);
    }
    
    /**
     * Add a library with the given path and name
     * @param libraryPath
     * @param libraryName
     */
    public void addLibraryMapping(Path libraryPath, String libraryName) {
        this.libraryMap.put(libraryPath, libraryName);
    }
    
    public void setName(String newName) { this.name = newName; }
    public void setAllowsLengthOptimization(boolean alo) { this.allowsLengthOptimization = alo; }
    public void setPrivileged(boolean isPrivileged) { this.privileged = isPrivileged; }
    public void setOrigin(ASMValue newOrigin) { this.origin = newOrigin; }
    
    public String getName() { return this.name; }
    public boolean allowsLengthOptimization() { return this.allowsLengthOptimization; }
    public boolean isPrivileged() { return this.privileged; }
    public ASMValue getOrigin() { return this.origin; }
    public List<ASMComponent> getComponents() { return this.components; }
    public Map<Path, String> getLibraryMap() { return this.libraryMap; }
    
}
