package notsotiny.nstasm;

import java.nio.file.Path;

/**
 * Assembly options
 * fields are mutable - e.g. entrySymbol gets changed during name compaction
 * @param allowMnemonicCorrection If true, malformed instructions may have their mnemonic corrected - e.g. MOV_RIM D:A, B:C -> MOVW_RIM D:A, B:C or ADD_RIM word [D:A], 2 -> ADD_RIM_I8 word [D:A], 2
 * @param optimizeInstructionWidth If true, optimize instruction widths - e.g. JMP_I32 -16 -> JMP_I8 -16
 * @param debugFriendlyOutput If true, outputs qualified names rather than systematic identifiers
 * @param entrySymbolName Qualified name of the entry symbol
 * @param stdlibPath Path of standard library for locating files. Used when assembling from source.
 */
public class AssemblyOptions {
    
    private boolean allowMnemonicCorrection,
                    optimizeInstructionWidth,
                    debugFriendlyOutput;
    
    private String entrySymbol;
    
    private Path stdlibPath;
    
    private int maxResolutionIterations;
    
    /**
     * Assembly options (full)
     * @param allowMnemonicCorrection If true, malformed instructions may have their mnemonic corrected - e.g. MOV D:A, B:C -> MOVW D:A, B:C
     * @param optimizeInstructionWidth If true, optimize instruction widths
     * @param debugFriendlyOutput If true, outputs qualified names rather than systematic identifiers
     * @param entrySymbolName Qualified name of the entry symbol
     * @param stdlibPath Path of standard library for locating files. Used when assembling from source.
     */
    public AssemblyOptions(boolean allowMnemonicCorrection, boolean optimizeInstructionWidth, boolean debugFriendlyOutput, String entrySymbol, Path stdlibPath, int maxResolutionIterations) {
        this.allowMnemonicCorrection = allowMnemonicCorrection;
        this.optimizeInstructionWidth = optimizeInstructionWidth;
        this.debugFriendlyOutput = debugFriendlyOutput;
        this.entrySymbol = entrySymbol;
        this.stdlibPath = stdlibPath;
        this.maxResolutionIterations = maxResolutionIterations;
    }
    
    public boolean allowMnemonicCorrection() { return this.allowMnemonicCorrection; }
    public boolean optimizeInstructionWidth() { return this.optimizeInstructionWidth; }
    public boolean debugFriendlyOutput() { return this.debugFriendlyOutput; }
    public String entrySymbol() { return this.entrySymbol; }
    public Path stdlibPath() { return this.stdlibPath; }
    public int maxResolutionIterations() { return this.maxResolutionIterations; }
    
    public void setEntrySymbol(String name) { this.entrySymbol = name; }
    
}