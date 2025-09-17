package notsotiny.nstasm;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

import asmlib.util.FileLocator;
import asmlib.util.relocation.ExecWriter;
import asmlib.util.relocation.RenameableRelocatableObject;
import fr.cenotelie.hime.redist.ParseError;
import fr.cenotelie.hime.redist.ParseResult;
import notsotiny.nstasm.asmparts.ASMObject;
import notsotiny.nstasm.assembly.ASMObjectAssembler;
import notsotiny.nstasm.assembly.ASMOptimizer;
import notsotiny.nstasm.assembly.ASMParser;
import notsotiny.nstasm.assembly.RelocationInfo;
import notsotiny.nstasm.parser.NstassemblerLexer;
import notsotiny.nstasm.parser.NstassemblerParser;

/**
 * Assembler take 2
 * Now with saner organization and more macros
 */
public class NSTAssembler {
    
    private static Logger LOG = Logger.getLogger(NSTAssembler.class.getName());
    
    /**
     * Main
     * @param args
     * @throws AssemblyException 
     * @throws IOException 
     */
    public static void main(String[] args) throws IOException, AssemblyException {
        // Properties
        Properties properties = new Properties();
        
        // Load .properties config
        // stdlib location
        try(FileInputStream fis = new FileInputStream("lib.properties")) {
            properties.load(fis);
        } catch(IOException e) {
            LOG.severe("Could not read standard library properties file. Specify standard library location with 'stdlib' in \"lib.properties\".");
            return;
        }
        
        // assembler properties (default arguments)
        try(FileInputStream fis = new FileInputStream("assembler.properties")) {
            properties.load(fis);
        } catch(IOException e) {
            LOG.severe("Could not read configuration file \"assembler.properties\".");
            return;
        }
        
        // Configuration
        int flagCount = 0;
        boolean debug = booleanProperty(properties, "debug", false),
                optimizeWidth = booleanProperty(properties, "optimize_width", true),
                hasOutputDir = false,
                hasExecFile = false,
                hasCommandEntry = false,
                hasEntryLabel = !properties.getProperty("entry", "").equals("");
        
        String standardArg = properties.getProperty("stdlib", ""),
               inputFileArg = "",
               execFileArg = "",
               outputDirArg = "",
               entryLabelArg = properties.getProperty("entry", "");
        
        if(standardArg.equals("")) {
            LOG.severe("Could not find standard library path. Specify standard library location with 'stdlib' in \"lib.properties\".");
            return;
        }
        
        // Process arguments
        // Do we have required args
        if(args.length < 1) {
            printUsage();
            return;
        }
        
        out:
        while(true) {
            switch(args[flagCount++]) {
                case "-d":
                    // debug flag
                    debug = true;
                    break;
                
                case "-x":
                    // exec file
                    hasExecFile = true;
                    execFileArg = args[flagCount++];
                    break;
                
                case "-e":
                    // Entry label
                    hasEntryLabel = true;
                    hasCommandEntry = true;
                    entryLabelArg = args[flagCount++];
                    break;
                
                case "-o":
                    // Output directory
                    hasOutputDir = true;
                    outputDirArg = args[flagCount++];
                    break;
                
                case "-oiw":
                    // Optimize instruction width
                    optimizeWidth = true;
                    break;
                
                case "-noiw":
                    // Don't optimize instruction width
                    // included because default is optimize = true
                    optimizeWidth = false;
                    break;
                
                default:
                    // Not a flag
                    flagCount--;
                    break out;
            }
        }
        
        inputFileArg = args[flagCount];
        
        // Assemble away
        Path sourcePath = Paths.get(inputFileArg),
             sourceDir = sourcePath.toAbsolutePath().getParent();
        
        if(!hasCommandEntry) {
            String sourceName = sourcePath.getFileName().toString();
            sourceName = sourceName.substring(0, sourceName.lastIndexOf("."));
            
            entryLabelArg = sourceName + ".entry";
            //LOG.info(entryLabelArg);
        }
        
        AssemblyOptions options = new AssemblyOptions(true, optimizeWidth, debug, entryLabelArg, Paths.get(standardArg), 8);
        List<RenameableRelocatableObject> objects = assembleSource(sourcePath, options);
        
        // Write object files
        LOG.info("Writing object files...");
        
        Path outDir = hasOutputDir ? Paths.get(outputDirArg) : sourceDir.resolve("out");
        
        if(hasExecFile) {
            Path execPath = Paths.get(execFileArg);
            
            // Relative to source by default
            if(!execPath.isAbsolute()) {
                execPath = sourceDir.resolve(execPath);
            }
            
            ExecWriter.write(objects, outDir, execPath, options.entrySymbol(), LOG);
        } else {
            ExecWriter.write(objects, outDir, LOG);
        }
    }
    
    /**
     * Assemble from a source file
     * @param file
     * @param options
     * @return
     * @throws IOException 
     * @throws AssemblyException 
     */
    public static List<RenameableRelocatableObject> assembleSource(Path file, AssemblyOptions options) throws IOException, AssemblyException {
        LOG.info("Assembling from source file " + file);
        
        // Setup file location/queue
        List<RenameableRelocatableObject> objects = new ArrayList<>();
        FileLocator locator = new FileLocator(file.toAbsolutePath().getParent(), options.stdlibPath().toAbsolutePath(), List.of(), List.of());
        boolean found = locator.addFile(file.toAbsolutePath());
        
        if(!found) {
            LOG.severe("Could not find initial souce file " + file);
            throw new FileNotFoundException(file + "");
        }
        
        // Name -> source
        Map<String, Path> libraryMap = new HashMap<>();
        
        // Rather than failing immediately, process as much as possible for more useful feedback
        boolean errorsEncountered = false;
        
        // Process files
        while(locator.hasUnconsumed()) {
            // Get file
            Path workingFile = locator.consume();
            LOG.fine("Processing file " + workingFile);
            
            RenameableRelocatableObject obj = null;
            
            // What do we do with it
            String fileExt = workingFile.toString();
            fileExt = fileExt.substring(fileExt.lastIndexOf("."));
            
            switch(fileExt) {
                case ".obj":
                    // Object file. Load it.
                    obj = new RenameableRelocatableObject(workingFile.toFile(), null);
                    break;
                
                case ".asm":
                    // Assembly object. Assemble it.
                    try {
                        obj = assembleFile(workingFile, locator, options);
                    } catch(AssemblyException e) {
                        errorsEncountered = true;
                    }
                    break;
                
                // TODO: potentially integrate nstl compiler
                
                default:
                    LOG.severe("Unexpected file type " + workingFile);
                    throw new AssemblyException();
            }
            
            // If we got a result
            if(obj != null) {
                libraryMap.put(obj.getName(), workingFile);
                objects.add(obj);
            }
        }
        
        // Did things go wrong
        if(errorsEncountered) {
            LOG.severe("Encountered errors processing files for " + file + ". See severe logs above.");
            throw new AssemblyException();
        }
        
        // Library name unification
        LOG.fine("Unifying library names");
        RenameableRelocatableObject.unifyNames(objects, libraryMap, LOG);
        
        // Compact names
        if(!options.debugFriendlyOutput()) {
            LOG.fine("Compacting names");
            options.setEntrySymbol(RenameableRelocatableObject.compactNames(objects, Set.of("ORIGIN"), options.entrySymbol()));
        }
        
        return objects;
    }
    
    /**
     * Assemble an object, adding its dependencies to the locator
     * @param file
     * @param locator
     * @param options
     * @return
     */
    public static RenameableRelocatableObject assembleFile(Path file, FileLocator locator, AssemblyOptions options) throws IOException, AssemblyException {
        LOG.info("Assembling file " + file);
        
        // Get contents
        try(InputStreamReader isr = new InputStreamReader(Files.newInputStream(file))) {
            // Parse contents
            ParseResult result = new NstassemblerParser(new NstassemblerLexer(isr)).parse();
            
            // did it work
            if(result.getErrors().size() != 0) {
                // no
                LOG.severe("Encountered errors parsing " + file);
                
                for(ParseError e : result.getErrors()) {
                    LOG.severe("ParseError: " + e);
                }
                
                throw new AssemblyException();
            } else {
                // yes, assemble :)
                String name = file.getFileName().toString();
                name = name.substring(0, name.lastIndexOf("."));
                ASMObject parsedObject = ASMParser.parseASM(result.getRoot(), name, locator);
                RelocationInfo relocationInfo = ASMObjectAssembler.assembleObject(parsedObject, options);
                return relocationInfo.toRRObject();
            }
        }
    }
    
    /**
     * Assemble from a list of ASMObjects
     * Does not unify library names or relocation symbol names
     * @param objects
     * @param options
     * @return
     */
    public static List<RenameableRelocatableObject> assembleObjects(List<ASMObject> objects, AssemblyOptions options) throws AssemblyException {
        LOG.info("Assembling from object list");
        
        // Assemble each object
        List<RenameableRelocatableObject> rrObjects = new ArrayList<>(objects.size());
        
        for(ASMObject aObject : objects) {
            RelocationInfo result = ASMObjectAssembler.assembleObject(aObject, options);
            rrObjects.add(result.toRRObject());
        }
        
        return rrObjects;
    }
    
    /**
     * Assemble an ASMObject
     * @param object
     * @param options
     * @return
     * @throws AssemblyException
     */
    public static RenameableRelocatableObject assembleObject(ASMObject object, AssemblyOptions options) throws AssemblyException {
        RelocationInfo result = ASMObjectAssembler.assembleObject(object, options);
        return result.toRRObject();
    }
    
    /**
     * Returns a property as a boolean
     * @param properties
     * @param key
     * @param def
     * @return
     */
    private static boolean booleanProperty(Properties properties, String key, boolean def) {
        return Boolean.parseBoolean(properties.getProperty(key, "" + def));
    }
    
    /**
     * Print usage
     */
    private static void printUsage() {
        System.out.println("Usage: NSTAssembler [options] <input file>");
        System.out.println("Flags:");
        System.out.println("\t-d\tEnable debug-friendly object files. If enabled, object files contain the full names of labels, leading to much larger files.");
        System.out.println("\t-x <exec file>\t\tExec File. Specifies the .oex file to ouput. Default <input file>.oex");
        System.out.println("\t-e <entry label>\tEntry. Specifies the label to enter with");
        System.out.println("\t-o <output directory>\tOutput. Specifies the location of the output object file. Default <working directory>\\out");
        System.out.println("\t-oiw\tOptimize instruction width. If enabled, opcodes are substituted for equivalent shortcut opcodes to reduce instruction width.");
        System.out.println("\t-noiw\tDon't optimize instruction width.");
    }
    
}
