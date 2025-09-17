package notsotiny.nstasm.assembly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import notsotiny.lib.util.MapUtil;
import notsotiny.nstasm.asmparts.ASMComponent;
import notsotiny.nstasm.asmparts.ASMInstruction;
import notsotiny.nstasm.asmparts.ASMLabel;
import notsotiny.nstasm.asmparts.ASMObject;
import notsotiny.nstasm.asmparts.ASMReference;
import notsotiny.nstasm.asmparts.ASMRepetition;

/**
 * Container for resolution data
 */
public class ASMResolver {
    
    public static final Logger LOG = Logger.getLogger(ASMResolver.class.getName());
    
    /*
     * To resolve a component, we need to know
     * - The component's start offset, for placing incoming references
     * - The component's end offset, for $ references
     * - The previous instruction's end offset, for @ references
     * - The offsets of object-local labels
     * To apply length optimization to an instruction, we need to know
     * - The resolved value of the source immediate or memory offset 
     * To output a resolvable object, we need
     * - The resolved bytes
     * - The offsets of object-local labels (outgoing references)
     * - The offsets of incoming references
     */
    
    private static final ResolvedImmediate UNRESOLVED = new ResolvedImmediate(false, 0);
    
    ASMObject obj;
    
    // For each component in an object, one or more inner component starts, ends, and previous instruction ends
    // The 'building' versions are built for the next iteration to use, and the non-'building' versions are used during this iteration
    // Most components will have a SingleInteger. Repetitions will have a MultipleInteger with a value for each inner component
    MultiList<Integer> componentStarts, buildingComponentStarts,        // using and building
                       componentEnds, buildingComponentEnds,            // using and building
                       componentPrevInsts, buildingComponentPrevInsts;  // using and building
    
    Map<String, Integer> labelOffsetMap, buildingLabelOffsetMap; // using and building
    
    MultiList<ResolvedImmediate> instructionImmediates; // building only
    
    List<Byte> resolvedBytes; // building only
    
    Map<String, List<Integer>> labelPlacementMap; // building only
    
    // Mid-resolution state
    boolean changed;
    
    List<Integer> componentIndices;
    
    int currentOffs, prevInstOffs, repetitionStride;
    
    ResolvedImmediate instImm;
    
    /**
     * Creates a ResolutionState with the given using lists
     * @param componentStarts
     * @param componentEnds
     * @param componentPrevInsts
     * @param labelOffsetMap
     */
    ASMResolver(MultiList<Integer> componentStarts, MultiList<Integer> componentEnds, MultiList<Integer> componentPrevInsts, Map<String, Integer> labelOffsetMap) {
        this.componentStarts = componentStarts;
        this.componentEnds = componentEnds;
        this.componentPrevInsts = componentPrevInsts;
        this.labelOffsetMap = labelOffsetMap;
        
        int numComponents = componentStarts.size();
        this.buildingComponentStarts = new MultiList<>(numComponents, 0);
        this.buildingComponentEnds = new MultiList<>(numComponents, 0);
        this.buildingComponentPrevInsts = new MultiList<>(numComponents, 0);
        this.buildingLabelOffsetMap = new HashMap<>((int)(labelOffsetMap.size() * 1.5));
        
        this.instructionImmediates = new MultiList<>(numComponents, UNRESOLVED);
        this.resolvedBytes = new ArrayList<>(numComponents * 2);
        this.labelPlacementMap = new HashMap<>();
        
        this.componentIndices = new ArrayList<>();
        
        this.currentOffs = 0;
        this.prevInstOffs = 0;
        this.repetitionStride = 1;
        this.instImm = UNRESOLVED;
        
        // At least two passes are needed
        this.changed = true;
    }
    
    /**
     * Creates a ResolutionState with the given state's built state as using state
     * @param prevState
     */
    ASMResolver(ASMResolver prevState) {
        this(prevState.buildingComponentStarts, prevState.buildingComponentEnds, prevState.buildingComponentPrevInsts, prevState.buildingLabelOffsetMap);
        
        // This is at least the second pass
        this.changed = false;
    }
    
    /**
     * Perform a resolution pass for the given object
     * @param o
     * @throws UnresolvableException 
     */
    public void resolve(ASMObject o) throws UnresolvableException {
        this.obj = o;
        
        // Process each component
        this.componentIndices.add(0);
        
        // Resolve origin
        if(o.getOrigin() != null) {
            long origin = o.getOrigin().getValue(this);
            
            if(origin != -1) {
                this.buildingLabelOffsetMap.put("ORIGIN", (int) origin);
            }
        }
        
        // Resolve components
        for(ASMComponent comp : o.getComponents()) {
            resolve(comp, this.buildingComponentStarts, this.buildingComponentEnds, this.buildingComponentPrevInsts, this.instructionImmediates);
        }
    }
    
    /**
     * Increment working index
     */
    private void incrementIndex() {
        int i = this.componentIndices.size() - 1;
        this.componentIndices.set(i, this.componentIndices.get(i) + 1);
    }
    
    /**
     * Gets the current repetition index
     * @return
     */
    public int getInnermostIndex() {
        return this.componentIndices.getLast() / this.repetitionStride;
    }
    
    /**
     * Resolve a component using the given building lists
     * @param comp
     * @param starts
     * @param ends
     * @param prevInsts
     * @param imms
     * @throws UnresolvableException
     */
    private void resolve(ASMComponent comp, MultiList<Integer> starts, MultiList<Integer> ends, MultiList<Integer> prevInsts, MultiList<ResolvedImmediate> imms) throws UnresolvableException {
        if(comp instanceof ASMLabel lbl) {
            // Label = place
            if(this.buildingLabelOffsetMap.containsKey(lbl.getName())) {
                LOG.severe("Duplicate label " + lbl.getName());
                throw new UnresolvableException();
            }
            
            LOG.finest(lbl.getName() + ":");
            this.buildingLabelOffsetMap.put(lbl.getName(), this.currentOffs);
        } else if(comp instanceof ASMRepetition rep) {
            // Repetition. Resolve to component list and resolve those
            List<ASMComponent> innerComponents = rep.getAllComponents(this);
            this.repetitionStride = rep.getRepeatedComponents().size();
            
            // Did the number of repetitions change?
            this.changed |= this.componentStarts.getMultiple(this.componentIndices).size() != innerComponents.size();
            
            // Resolve inner
            MultiList<Integer> innerStarts = new MultiList<>(innerComponents.size(), 0),
                               innerEnds = new MultiList<>(innerComponents.size(), 0),
                               innerPrevInsts = new MultiList<>(innerComponents.size(), 0);
 
            MultiList<ResolvedImmediate> innerImms = new MultiList<>(innerComponents.size(), UNRESOLVED);
            
            this.componentIndices.add(0);
            
            for(ASMComponent innerComp : innerComponents) {
                int stride = this.repetitionStride;
                
                resolve(innerComp, innerStarts, innerEnds, innerPrevInsts, innerImms);
                
                this.repetitionStride = stride;
            }
            
            this.componentIndices.removeLast();
            
            // Add results
            starts.add(innerStarts);
            ends.add(innerEnds);
            prevInsts.add(innerPrevInsts);
            imms.add(innerImms);
            incrementIndex();
        } else {
            // Normal component
            this.instImm = UNRESOLVED;
            
            // Resolve single
            int size = resolveSingle(comp);
            
            // Record
            imms.add(this.instImm);
            starts.add(this.currentOffs);
            
            this.currentOffs += size;
            
            ends.add(this.currentOffs);
            prevInsts.add(this.prevInstOffs);
            
            if(comp instanceof ASMInstruction) {
                this.prevInstOffs = this.currentOffs;
            }
            
            incrementIndex();
        }
    }
    
    /**
     * Resolve a single component
     * @param c
     * @return size of resolved component
     * @throws UnresolvableException
     */
    private int resolveSingle(ASMComponent c) throws UnresolvableException {
        // resolve
        List<Byte> bytes = c.getBytes(this);
        
        // report
        if(LOG.isLoggable(Level.FINEST)) {
            StringBuilder sb = new StringBuilder(String.format("%08X: ", this.currentOffs));
            
            for(int i = 0; i < 8; i++) {
                if(i < bytes.size()) {
                    sb.append(String.format("%02X ", bytes.get(i)));
                } else {
                    sb.append("   ");
                }
            }
            
            sb.append(c);
            
            LOG.finest(sb.toString());
        }
        
        // Is it different?
        int sizeBefore = this.componentEnds.getSingle(this.componentIndices) - this.componentStarts.getSingle(this.componentIndices);
        this.changed |= sizeBefore != bytes.size();
        
        // add to result
        this.resolvedBytes.addAll(bytes);
        return bytes.size();
    }
    
    /**
     * Place a reference relative to the current component start
     * @param name
     * @param offs
     */
    public void placeReference(String name, int offs) {
        if(this.labelOffsetMap.containsKey(name)) {
            name = this.obj.getName() + "." + name;
        }
        
        MapUtil.getOrCreateList(this.labelPlacementMap, name).add(this.componentStarts.getSingle(this.componentIndices) + offs);
    }
    
    /**
     * Set a resolved value for the current component's immediate
     * @param value
     */
    public void setImmediate(long value) {
        this.instImm = new ResolvedImmediate(true, value);
    }
    
    /**
     * Returns true if ref is local to the object
     * @param ref
     * @return
     */
    public boolean isLocal(ASMReference ref) {
        return ref.getName().equals("") || this.labelOffsetMap.containsKey(ref.getName());
    }
    
    /**
     * Get the offset value of a reference
     * NORMAL:              offset from object start
     * RELATIVE_CURRENT:    offset from current component end
     * RELATIVE_PREVIOUS:   offset from last instruction end
     * @param ref
     * @return
     */
    public int getOffset(ASMReference ref) {
        // Get base
        int relativeFrom = switch(ref.getType()) {
            case RELATIVE_CURRENT   -> this.componentEnds.getLast(this.componentIndices);
            case RELATIVE_PREVIOUS  -> this.componentPrevInsts.getFirst(this.componentIndices);
            case RELATIVE_START     -> this.componentStarts.getFirst(this.componentIndices);
            default                 -> 0;
        };
        
        // Name empty?
        if(ref.getName().equals("")) {
            // Empty name valid for relative
            return relativeFrom;
        }
        
        // Not empty does it exist?
        if(!isLocal(ref)) {
            throw new IllegalArgumentException("Not a local reference: " + ref);
        }
        
        // It exists. Get value
        return this.labelOffsetMap.get(ref.getName()) - relativeFrom;
    }
    
}
