package notsotiny.nstasm.assembly;

import java.util.List;

import fr.cenotelie.hime.redist.ASTNode;

/**
 * A AST-level macro in the assembly
 */
public class ASMMacro {
    
    private String name;
    
    private List<String> argNames;
    
    private ASTNode contents;
    
    /**
     * Create a macro
     * @param name
     * @param argNames
     * @param contents
     */
    public ASMMacro(String name, List<String> argNames, ASTNode contents) {
        this.name = name;
        this.argNames = argNames;
        this.contents = contents;
    }
    
    public String getName() { return this.name; }
    public List<String> getArgNames() { return this.argNames; }
    public ASTNode getContents() { return this.contents; }
    
}
