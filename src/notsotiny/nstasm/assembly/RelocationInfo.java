package notsotiny.nstasm.assembly;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import asmlib.util.relocation.RelocatableObject.Endianness;
import asmlib.util.relocation.RenameableRelocatableObject;

/**
 * Information used for relocation
 */
public class RelocationInfo {
    
    private String name;
    
    private HashMap<String, List<Integer>> incomingReferences;
    
    private HashMap<String, Integer> outgoingReferences,
                                     incomingReferenceWidths,
                                     outgoingReferenceWidths;
    
    private HashMap<File, String> libraryMap;
    
    private List<Byte> bytes;
    
    /**
     * Create an empty info object with the given name
     * @param name object/library name
     * @param libraryMap Map of included libraries to their local name
     */
    public RelocationInfo(String name, Map<Path, String> libraryMap) {
        this.name = name;
        
        this.incomingReferences = new HashMap<>();
        this.outgoingReferences = new HashMap<>();
        this.incomingReferenceWidths = new HashMap<>();
        this.outgoingReferenceWidths = new HashMap<>();
        this.bytes = new ArrayList<>();
        
        this.libraryMap = new HashMap<>();
        
        for(Entry<Path, String> e : libraryMap.entrySet()) {
            this.libraryMap.put(e.getKey().toFile(), e.getValue());
        }
    }
    
    /**
     * Add an outgoing reference with the given offset
     * @param name
     * @param offset
     */
    public void addOutgoingReference(String name, int offset) {
        this.outgoingReferences.put(name, offset);
        this.outgoingReferenceWidths.put(name, 4);
    }
    
    /**
     * Add an incoming reference with the given offset
     * @param name
     * @param offset
     */
    public void addIncomingReference(String name, int offset) {
        getOrCreateList(this.incomingReferences, name).add(offset);
        this.incomingReferenceWidths.put(name, 4);
    }
    
    /**
     * Add data to the object code
     * @param data
     */
    public void addData(List<Byte> data) {
        this.bytes.addAll(data);
    }
    
    /**
     * Convert to renameable relocatable object
     * @return
     */
    public RenameableRelocatableObject toRRObject() {
        // List to array
        byte[] objectCode = new byte[this.bytes.size()];
        
        for(int i = 0; i < objectCode.length; i++) {
            objectCode[i] = this.bytes.get(i);
        }
        
        // Construct object
        return new RenameableRelocatableObject(
            Endianness.LITTLE,
            this.name,
            4,
            this.incomingReferences,
            this.outgoingReferences,
            this.incomingReferenceWidths,
            this.outgoingReferenceWidths,
            objectCode,
            false,
            this.libraryMap
        );
    }
    
    /**
     * Copied from MapUtil in the compiler project
     * Get the given list, or create it
     * @param <K>
     * @param <V>
     * @param map
     * @param key
     * @return
     */
    public static <K, V> List<V> getOrCreateList(Map<K, List<V>> map, K key) {
        if(map.containsKey(key)) {
            return map.get(key);
        } else {
            List<V> list = new ArrayList<V>();
            map.put(key, list);
            return list;
        }
    }
    
}
