package notsotiny.nstasm.assembly;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Logger;

import notsotiny.nstasm.AssemblyException;
import notsotiny.nstasm.AssemblyOptions;
import notsotiny.nstasm.asmparts.ASMComponent;
import notsotiny.nstasm.asmparts.ASMLabel;
import notsotiny.nstasm.asmparts.ASMObject;

/**
 * Assembles ASMObjects into relocatable results
 */
public class ASMObjectAssembler {
    
    private static Logger LOG = Logger.getLogger(ASMObjectAssembler.class.getName());
    
    /**
     * Assembles an ASMObject into a relocatable result
     * @param object
     * @param options
     * @return
     * @throws UnresolvableException 
     * @throws AssemblyException 
     */
    public static RelocationInfo assembleObject(ASMObject object, AssemblyOptions options) throws AssemblyException {
        LOG.fine("Assembling object " + object.getName());
        
        /*
         * General method:
         * 1. Use components' maximum size to establish initial labels and component sizes
         * 2. Resolve with current labels, building new labels and component sizes
         * 3. If labels/sizes changed during (2), repeat (2)
         * Most of this process is in the ResolutionState
         */
        
        // Initial ResolutionState
        List<ASMComponent> comps = object.getComponents();
        
        MultiList<Integer> initialComponentStarts = new MultiList<>(comps.size(), 0),
                           initialComponentEnds = new MultiList<>(comps.size(), 0),
                           initialComponentPrevInsts = new MultiList<>(comps.size(), 0);
        Map<String, Integer> initialLabelOffsetMap = new HashMap<>();
        
        for(ASMComponent c : object.getComponents()) {
            if(c instanceof ASMLabel lbl) {
                initialLabelOffsetMap.put(lbl.getName(), 0);
            } else {
                initialComponentStarts.add(0);
                initialComponentEnds.add(0);
                initialComponentPrevInsts.add(0);
            }
        }
        
        // Initial validation
        if(!ASMValidator.validateUnresolved(object, initialLabelOffsetMap.keySet(), options)) {
            LOG.severe("Found invalid component(s) in " + object.getName());
            throw new AssemblyException();
        }
        
        ASMResolver rState = new ASMResolver(initialComponentStarts, initialComponentEnds, initialComponentPrevInsts, initialLabelOffsetMap);
        
        int resolutionIters = -1;
        int optimizeIters = 0;
        while(true) {
            LOG.finer("Performing resolution pass " + (resolutionIters + optimizeIters));
            
            // Resolve with current offsets
            try {
                rState.resolve(object);
            } catch(UnresolvableException e) {
                LOG.severe("Unresolvable");
                e.printStackTrace();
                throw new AssemblyException();
            }
            
            // is the resolution in steady state
            if(!rState.changed) {
                optimizeIters++;
                boolean changed = ASMOptimizer.optimizeWidth(object, rState.instructionImmediates, options);
                
                if(!changed) {
                    break;
                }
            } else {
                resolutionIters++;
            }
            
            // iterate
            rState = new ASMResolver(rState);
            
            if(resolutionIters > options.maxResolutionIterations() || optimizeIters > options.maxResolutionIterations()) {
                LOG.severe("Resolution did not converge within maximum iterations");
                throw new AssemblyException();
            }
        }
        
        // convert to relocation info
        RelocationInfo reloc = new RelocationInfo(object.getName(), object.getLibraryMap());
        
        reloc.addData(rState.resolvedBytes);
        
        for(Entry<String, Integer> entry : rState.buildingLabelOffsetMap.entrySet()) {
            reloc.addOutgoingReference(entry.getKey(), entry.getValue());
        }
        
        for(Entry<String, List<Integer>> entry : rState.labelPlacementMap.entrySet()) {
            for(int offs : entry.getValue()) {
                reloc.addIncomingReference(entry.getKey(), offs);
            }
        }
        
        if(object.isPrivileged()) {
            reloc.addOutgoingReference("PRIVILEGED", 1);
        }
        
        // origin handled by rState
        
        return reloc;
    }
    
}
