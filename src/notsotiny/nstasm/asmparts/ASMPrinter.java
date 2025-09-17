package notsotiny.nstasm.asmparts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map.Entry;

import notsotiny.lib.printing.Printer;

public class ASMPrinter {
    
    /**
     * Print an ASMObject
     * @param printer
     * @param obj
     * @throws IOException
     */
    public static void printObject(Printer printer, ASMObject obj) throws IOException {
        // Print parameters
        printer.println(";\n; " + obj.getName() + "\n;");
        
        if(!obj.allowsLengthOptimization()) {
            printer.println("; No length optimization");
        }
        
        if(obj.isPrivileged()) {
            printer.println("%privileged");
        }
        
        if(obj.getOrigin() != null) {
            printer.println("%org " + obj.getOrigin());
        }
        
        printer.println("");
        
        // Print library inclusions
        for(Entry<Path, String> lib : obj.getLibraryMap().entrySet()) {
            printer.println("%include \"" + lib.getKey() + "\" as " + lib.getValue());
        }
        
        if(obj.getLibraryMap().size() > 0) {
            printer.println("");
        }
        
        boolean firstLabel = true;
        
        // Print each component
        for(ASMComponent c : obj.getComponents()) {
            firstLabel &= !printComponent(printer, c, 1, firstLabel);
        }
    }
    
    /**
     * Print a component
     * @param c
     * @param indentDepth
     * @param firstLabel
     * @return true if label involved
     */
    private static boolean printComponent(Printer printer, ASMComponent c, int indentDepth, boolean firstLabel) throws IOException {
        switch(c) {
            case ASMLabel _:
                if(!firstLabel) {
                    printer.println("");
                }
                
                indent(printer, indentDepth - 1);
                printer.println(c.toString() + ":");
                return true;
                
            case ASMRepetition rep:
                boolean label = false;
                
                indent(printer, indentDepth);
                printer.println("repeat " + rep.getNumRepetitions());
                
                for(ASMComponent ic : rep.getRepeatedComponents()) {
                    label |= printComponent(printer, ic, indentDepth + 1, label);
                }
                
                return label;
            
            default:
                indent(printer, indentDepth);
                printer.println(c.toString());
                return false;
        }
    }
    
    /**
     * Indent by the given amount
     * @param printer
     * @param depth
     * @throws IOException
     */
    private static void indent(Printer printer, int depth) throws IOException {
        StringBuilder sb = new StringBuilder();
        
        for(int i = 0; i < depth; i++) {
            sb.append("\t");
        }
        
        printer.print(sb.toString());
    }
    
}
