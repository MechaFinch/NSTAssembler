package notsotiny.nstasm.assembly;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

import fr.cenotelie.hime.redist.ASTNode;

/**
 * Context
 * Maintains mappings from identifiers to objects with macro scoping
 */
public class ASMContext {
    
    private static Logger LOG = Logger.getLogger(ASMContext.class.getName());
    
    private class ContextEntry {
        // Substitutions for macro arguments
        public Map<String, ASTNode> substitutionMap;
        
        // Name of the macro invoked
        public String macroName;
        
        /**
         * A lexical scope entry
         * @param isMacro true if the scope comes from being in a macro
         * @param enclosingLabel name of the scope
         */
        public ContextEntry(String name) {
            this.macroName = name;
            this.substitutionMap = new HashMap<>();
        }
    }
    
    private Map<String, ASMMacro> macroMap;
    
    private String topLevelLabel;
    
    private Deque<ContextEntry> contextStack;
    
    // Substitution depth allows macro parameter names to overlap
    private int uniqueCounter, substitutionDepth;
    
    /**
     * Empty context
     */
    public ASMContext() {
        this.topLevelLabel = "";
        this.macroMap = new HashMap<>();
        this.contextStack = new ArrayDeque<>();
        this.uniqueCounter = 0;
        this.substitutionDepth = 0;
    }
    
    /**
     * Add a macro (global)
     * @param macro
     */
    public void addMacro(ASMMacro macro) {
        // Macros are top-level only, so no scoping
        this.macroMap.put(macro.getName(), macro);
    }
    
    /**
     * Add a substitution to the current lexical scope
     * @param name
     * @param node
     */
    public void addSubstitution(String name, ASTNode node) {
        this.contextStack.peek().substitutionMap.put(name, node);
    }
    
    /**
     * Returns true if the given name is that of a macro
     * @param name
     * @return
     */
    public boolean isMacro(String name) {
        return this.macroMap.containsKey(name);
    }
    
    /**
     * Get the macro with the given name, or null
     * @param name
     * @return
     */
    public ASMMacro getMacro(String name) {
        return this.macroMap.get(name);
    }
    
    /**
     * Return the node for the given name, or null
     * @param name
     * @return
     */
    private ASTNode findSubstitution(String name) {
        // Get from the ith macro from the top
        int i = 0;
        
        for(ContextEntry ce : this.contextStack) {
            if(i++ == this.substitutionDepth) {
                return ce.substitutionMap.get(name);
            }
        }
        
        return null;
    }
    
    /**
     * Return the node for the given name, and increment the substitution depth
     * @param name
     * @return
     */
    public ASTNode getSubstitution(String name) {
        ASTNode node = findSubstitution(name);
        
        if(node == null) {
            return null;
        }
        
        this.substitutionDepth++;
        LOG.finest("Increased substiution depth to " + this.substitutionDepth);
        return node;
    }
    
    /**
     * Returns true if the given name is that of a substitution
     * @param name
     * @return
     */
    public boolean isSubstituted(String name) {
        return findSubstitution(name) != null;
    }
    
    /**
     * Get the enclosing name of the current scope
     * @return
     */
    public String getEnclosingName(boolean includeMacros) {
        if(includeMacros) {
            String name = "";
            
            for(ContextEntry ce : this.contextStack) {
                name = "%" + ce.macroName + name; 
            }
            
            return this.topLevelLabel + name;
        } else {
            return this.topLevelLabel;
        }
    }
    
    /**
     * Entry a new lexical scope with the given name
     * @param macroName
     */
    public void pushContext(String macroName) {
        // Generate unique name if macro
        macroName += "$" + this.uniqueCounter++;        
        this.contextStack.push(new ContextEntry(macroName));
        
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Pushed context " + getEnclosingName(true));
        }
    }
    
    /**
     * Set the top-level label
     * @param label
     */
    public void setTopLevelLabel(String label) {
        this.topLevelLabel = label;
    }
    
    /**
     * Leave current lexical scope
     */
    public void popContext() {
        try {
            if(LOG.isLoggable(Level.FINEST)) {
                LOG.finest("Popped context " + getEnclosingName(true));
            }
            
            this.contextStack.pop();
        } catch(NoSuchElementException e) {}
    }
    
    /**
     * pop numContexts contexts
     * @param numContexts
     */
    public void popContext(int numContexts) {
        for(int i = 0; i < numContexts; i++) {
            popContext();
        }
    }
    
    /**
     * Leave a number of substitutions
     * @param numSubstitutions
     */
    public void leaveSubstitution(int numSubstitutions) {
        this.substitutionDepth -= numSubstitutions;
        LOG.finest("Reduced substitution depth to " + this.substitutionDepth);
    }
    
    /**
     * Single call for popContext and leaveSubstitution
     * @param numContexts
     * @param numSubstitutions
     */
    public void leave(int numContexts, int numSubstitutions) {
        popContext(numContexts);
        leaveSubstitution(numSubstitutions);
    }
    
}
