/**
 * 
 */
/**
 * 
 */
module NSTAssembler {
    requires transitive NotSoTiny;
    requires java.logging;
    requires transitive AssemblerLib;
    requires transitive NSTSharedLibrary;
    
    opens notsotiny.nstasm.parser;
    
    exports notsotiny.nstasm;
    exports notsotiny.nstasm.asmparts;
    exports notsotiny.nstasm.assembly;
}