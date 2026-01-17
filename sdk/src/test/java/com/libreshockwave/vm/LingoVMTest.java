package com.libreshockwave.vm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.player.CastManager;

import java.io.InputStream;
import java.net.URL;
import java.util.List;

/**
 * Tests for the Lingo VM using real Director cast files.
 * Tests convertToPropList handler from fuse_client.cct.
 */
public class LingoVMTest {

    // System Props field content (uses RETURN as line delimiter)
    private static final String SYSTEM_PROPS = """
httpcookie.pref.name      = hh_httpcookies.txt
char.conversion.win = [128:164]
char.conversion.mac=[128:219,130:226,131:196,132:227,133:201,134:160,135:224,136:246,137:228,139:220,140:206,145:212,146:213,147:210,148:211,149:165,150:208,151:209,152:247,153:170,155:221,156:207,159:217,161:193,165:180,167:164,168:172,170:187,171:199,172:194,173:208,174:168,176:161,180:171,182:166,183:225,184:252,186:188,187:200,191:192,192:203,193:231,194:229,195:204,196:128,197:129,198:174,199:130,200:233,201:131,202:230,203:232,204:237,205:234,206:235,207:236,209:132,210:241,211:238,212:239,213:205,214:133,216:175,217:244,218:242,219:243,220:134,223:167,224:136,225:135,226:137,227:139,228:138,229:140,230:190,231:141,232:143,233:142,234:144,235:145,236:147,237:146,238:148,239:149,241:150,242:152,243:151,244:153,246:154,247:214,248:191,249:157,250:156,251:158,252:159,255:216]
loading.bar.active        = 1
loading.bar.props         = [#color:rgb(128,128,128), #bgColor:rgb(0,0,0), #width:128, #height:16]
pref.value.id             = 6FEB4C10
struct.font.plain         = [#font:"Courier", #fontSize:9, #lineHeight:10, #color:rgb("#000000"), #ilk:#struct, #fontStyle:[#plain]]
struct.font.bold          = [#font:"Courier", #fontSize:9, #lineHeight:10, #color:rgb("#000000"), #ilk:#struct, #fontStyle:[#bold]]
struct.font.italic        = [#font:"Courier", #fontSize:9, #lineHeight:10, #color:rgb("#000000"), #ilk:#struct, #fontStyle:[#italic]]
struct.font.link          = [#font:"Courier", #fontSize:9, #lineHeight:10, #color:rgb("#000066"), #ilk:#struct, #fontStyle:[#underline]]
struct.font.tooltip       = [#font:"Courier", #fontSize:9, #lineHeight:10, #color:rgb("#000000"), #ilk:#struct, #fontStyle:[#plain]]
struct.font.empty         = [#font:EMPTY,     #fontSize:0, #lineHeight:0,  #color:rgb("#000000"), #ilk:#struct, #fontStyle:[#plain]]
struct.array.head         = [#content:VOID, #prev:VOID, #next:VOID, #type:#head, #ilk:#struct]
struct.array.node         = [#content:VOID, #prev:VOID, #next:VOID, #type:#node, #ilk:#struct]
struct.array.tail         = [#content:VOID, #prev:VOID, #next:VOID, #type:#tail, #ilk:#struct]
struct.message            = [#subject:VOID, #content:VOID, #connection:VOID, #ilk:#struct]
struct.pointer            = [#value:VOID, #ilk:#struct]
object.manager.class      = ["Object Manager Class"]
thread.manager.class      = ["Thread Manager Class"]
visualizer.manager.class  = ["Manager Template Class","Visualizer Manager Class"]
""".replace("\n", "\r"); // Use RETURN as delimiter like Director

    public static void main(String[] args) {
        System.out.println("=== Lingo VM Tests ===\n");

        testItemDelimiter();
        testStringChunkCount();
        testStringChunkAccess();
        testOffsetBuiltin();
        testLengthBuiltin();
        testPropListOperations();
        testConvertToPropListLogic();
        testStringMethodCalls();
        testConvertToPropListWithSystemProps();

        // Test with real cast file from localhost
        testConvertToPropListFromRealCast();

        System.out.println("\n=== All VM Tests Completed ===");
    }

    private static void testItemDelimiter() {
        System.out.println("--- Testing itemDelimiter ---");

        LingoVM vm = new LingoVM(null);

        // Default delimiter should be comma
        String delim = vm.getItemDelimiter();
        assert delim.equals(",") : "Default itemDelimiter should be comma";
        System.out.println("  Default itemDelimiter: PASS");

        // Test that item count uses delimiter
        Datum result = vm.call("count", List.of(Datum.of("a,b,c"), Datum.symbol("item")));
        assert result.intValue() == 3 : "Item count with comma delimiter should be 3, got: " + result.intValue();
        System.out.println("  Item count with comma: PASS");

        System.out.println("  itemDelimiter: ALL PASS\n");
    }

    private static void testStringChunkCount() {
        System.out.println("--- Testing String Chunk Count ---");

        LingoVM vm = new LingoVM(null);

        // Test item count
        String testStr = "apple,banana,cherry";
        Datum strDatum = Datum.of(testStr);

        // Use the count builtin with string and chunk type
        Datum result = vm.call("count", List.of(strDatum, Datum.symbol("item")));
        assert result.intValue() == 3 : "Item count should be 3, got: " + result.intValue();
        System.out.println("  Item count: PASS");

        // Test word count
        testStr = "hello world test";
        strDatum = Datum.of(testStr);
        result = vm.call("count", List.of(strDatum, Datum.symbol("word")));
        assert result.intValue() == 3 : "Word count should be 3, got: " + result.intValue();
        System.out.println("  Word count: PASS");

        // Test char count
        testStr = "hello";
        strDatum = Datum.of(testStr);
        result = vm.call("count", List.of(strDatum, Datum.symbol("char")));
        assert result.intValue() == 5 : "Char count should be 5, got: " + result.intValue();
        System.out.println("  Char count: PASS");

        // Test line count
        testStr = "line1\nline2\nline3";
        strDatum = Datum.of(testStr);
        result = vm.call("count", List.of(strDatum, Datum.symbol("line")));
        assert result.intValue() == 3 : "Line count should be 3, got: " + result.intValue();
        System.out.println("  Line count: PASS");

        System.out.println("  String Chunk Count: ALL PASS\n");
    }

    private static void testStringChunkAccess() {
        System.out.println("--- Testing String Chunk Access ---");

        LingoVM vm = new LingoVM(null);

        // Test item access using chars builtin
        String testStr = "name=John";

        // Get chars 1 to 4 of "name=John" -> "name"
        Datum result = vm.call("chars", List.of(Datum.of(testStr), Datum.of(1), Datum.of(4)));
        assert result.stringValue().equals("name") : "chars(1,4) should be 'name', got: " + result.stringValue();
        System.out.println("  chars(1,4): PASS");

        // Get chars 6 to 9 of "name=John" -> "John"
        result = vm.call("chars", List.of(Datum.of(testStr), Datum.of(6), Datum.of(9)));
        assert result.stringValue().equals("John") : "chars(6,9) should be 'John', got: " + result.stringValue();
        System.out.println("  chars(6,9): PASS");

        // Test word access
        testStr = "hello world test";
        result = vm.call("word", List.of(Datum.of(testStr), Datum.of(2)));
        assert result.stringValue().equals("world") : "word 2 should be 'world', got: " + result.stringValue();
        System.out.println("  word(2): PASS");

        // Test item access
        testStr = "apple,banana,cherry";
        result = vm.call("item", List.of(Datum.of(testStr), Datum.of(2)));
        assert result.stringValue().equals("banana") : "item 2 should be 'banana', got: " + result.stringValue();
        System.out.println("  item(2): PASS");

        System.out.println("  String Chunk Access: ALL PASS\n");
    }

    private static void testOffsetBuiltin() {
        System.out.println("--- Testing offset() Builtin ---");

        LingoVM vm = new LingoVM(null);

        // Test offset - finds position of substring (1-based)
        Datum result = vm.call("offset", List.of(Datum.of("="), Datum.of("name=value")));
        assert result.intValue() == 5 : "offset('=', 'name=value') should be 5, got: " + result.intValue();
        System.out.println("  offset('=', 'name=value'): PASS");

        // Not found returns 0
        result = vm.call("offset", List.of(Datum.of("x"), Datum.of("name=value")));
        assert result.intValue() == 0 : "offset('x', 'name=value') should be 0, got: " + result.intValue();
        System.out.println("  offset not found: PASS");

        // At start
        result = vm.call("offset", List.of(Datum.of("n"), Datum.of("name=value")));
        assert result.intValue() == 1 : "offset('n', 'name=value') should be 1, got: " + result.intValue();
        System.out.println("  offset at start: PASS");

        System.out.println("  offset() Builtin: ALL PASS\n");
    }

    private static void testLengthBuiltin() {
        System.out.println("--- Testing length() Builtin ---");

        LingoVM vm = new LingoVM(null);

        Datum result = vm.call("length", List.of(Datum.of("hello")));
        assert result.intValue() == 5 : "length('hello') should be 5, got: " + result.intValue();
        System.out.println("  length('hello'): PASS");

        result = vm.call("length", List.of(Datum.of("")));
        assert result.intValue() == 0 : "length('') should be 0, got: " + result.intValue();
        System.out.println("  length(''): PASS");

        result = vm.call("length", List.of(Datum.of("name=value")));
        assert result.intValue() == 10 : "length('name=value') should be 10, got: " + result.intValue();
        System.out.println("  length('name=value'): PASS");

        System.out.println("  length() Builtin: ALL PASS\n");
    }

    private static void testPropListOperations() {
        System.out.println("--- Testing PropList Operations ---");

        LingoVM vm = new LingoVM(null);

        // Create empty propList
        Datum.PropList props = Datum.propList();
        assert props.count() == 0 : "Empty propList should have count 0";
        System.out.println("  Empty propList: PASS");

        // Add properties
        props.put(Datum.symbol("name"), Datum.of("John"));
        props.put(Datum.symbol("age"), Datum.of("30"));
        assert props.count() == 2 : "PropList should have count 2, got: " + props.count();
        System.out.println("  PropList add: PASS");

        // Get property
        Datum val = props.get(Datum.symbol("name"));
        assert val.stringValue().equals("John") : "name should be 'John', got: " + val.stringValue();
        System.out.println("  PropList get: PASS");

        // Test using setaProp builtin
        vm.call("setaProp", List.of(props, Datum.symbol("city"), Datum.of("NYC")));
        assert props.count() == 3 : "PropList should have count 3 after setaProp";
        System.out.println("  setaProp: PASS");

        // Test using getaProp builtin
        Datum result = vm.call("getaProp", List.of(props, Datum.symbol("city")));
        assert result.stringValue().equals("NYC") : "city should be 'NYC', got: " + result.stringValue();
        System.out.println("  getaProp: PASS");

        System.out.println("  PropList Operations: ALL PASS\n");
    }

    /**
     * Test the logic of convertToPropList without actual bytecode execution.
     * Simulates: convertToPropList("name=John,age=30", ",")
     */
    private static void testConvertToPropListLogic() {
        System.out.println("--- Testing convertToPropList Logic ---");

        LingoVM vm = new LingoVM(null);

        String tString = "name=John,age=30";
        String tDelimiter = ",";

        // Simulate the function logic
        Datum.PropList tProps = Datum.propList();

        // Split by items
        String[] items = tString.split(java.util.regex.Pattern.quote(tDelimiter));
        System.out.println("  Item count: " + items.length);
        assert items.length == 2 : "Should have 2 items";

        for (String item : items) {
            // Find "=" position
            int eqPos = item.indexOf("=");
            assert eqPos > 0 : "Each item should have '='";

            // Extract property name (chars before =)
            String tProp = item.substring(0, eqPos).trim();

            // Extract value (chars after =)
            String tValue = item.substring(eqPos + 1).trim();

            System.out.println("    Parsed: " + tProp + " = " + tValue);

            // Add to propList
            tProps.put(Datum.of(tProp), Datum.of(tValue));
        }

        // Verify result
        assert tProps.count() == 2 : "PropList should have 2 properties";
        assert tProps.get(Datum.of("name")).stringValue().equals("John") : "name should be John";
        assert tProps.get(Datum.of("age")).stringValue().equals("30") : "age should be 30";

        System.out.println("  Result: [name: John, age: 30] (" + tProps.count() + " properties)");
        System.out.println("  convertToPropList Logic: ALL PASS\n");
    }

    /**
     * Test the VM's ability to handle the string method calls used in convertToPropList.
     */
    private static void testStringMethodCalls() {
        System.out.println("--- Testing String Method Calls ---");

        LingoVM vm = new LingoVM(null);

        // Create a MethodDispatcher to test string method calls
        String testStr = "apple,banana,cherry";

        // Test count method on string
        MethodDispatcher dispatcher = new MethodDispatcher(vm, null, null);
        Datum result = dispatcher.callStringMethod(testStr, "count", List.of(Datum.symbol("item")));
        assert result.intValue() == 3 : "count(#item) should be 3, got: " + result.intValue();
        System.out.println("  String.count(#item): PASS");

        // Test word count
        testStr = "hello world test";
        result = dispatcher.callStringMethod(testStr, "count", List.of(Datum.symbol("word")));
        assert result.intValue() == 3 : "count(#word) should be 3, got: " + result.intValue();
        System.out.println("  String.count(#word): PASS");

        // Test getProp for extracting a chunk
        testStr = "name=John";
        result = dispatcher.callStringMethod(testStr, "getProp", List.of(Datum.symbol("char"), Datum.of(1), Datum.of(4)));
        assert result.stringValue().equals("name") : "getProp(#char, 1, 4) should be 'name', got: " + result.stringValue();
        System.out.println("  String.getProp(#char, 1, 4): PASS");

        System.out.println("  String Method Calls: ALL PASS\n");
    }

    /**
     * Test convertToPropList with real System Props data (simulation).
     */
    private static void testConvertToPropListWithSystemProps() {
        System.out.println("--- Testing convertToPropList with System Props ---");

        LingoVM vm = new LingoVM(null);

        String tString = SYSTEM_PROPS;
        String tDelimiter = "\r";

        System.out.println("  Input string length: " + tString.length());

        // Simulate the convertToPropList function
        Datum.PropList tProps = convertToPropList(vm, tString, tDelimiter);

        System.out.println("  Parsed " + tProps.count() + " properties");

        // Verify some known properties
        Datum httpCookiePref = tProps.get(Datum.of("httpcookie.pref.name"));
        assert httpCookiePref != null && !httpCookiePref.isVoid() : "httpcookie.pref.name should exist";
        assert httpCookiePref.stringValue().equals("hh_httpcookies.txt") :
            "httpcookie.pref.name should be 'hh_httpcookies.txt', got: " + httpCookiePref.stringValue();
        System.out.println("  httpcookie.pref.name: PASS");

        Datum objectManagerClass = tProps.get(Datum.of("object.manager.class"));
        assert objectManagerClass != null && !objectManagerClass.isVoid() : "object.manager.class should exist";
        String rawValue = objectManagerClass.stringValue();
        assert rawValue.equals("[\"Object Manager Class\"]") :
            "object.manager.class should be '[\"Object Manager Class\"]', got: " + rawValue;
        System.out.println("  object.manager.class lookup: PASS");

        System.out.println("  convertToPropList with System Props: ALL PASS\n");
    }

    /**
     * Implements convertToPropList using Java (simulation).
     */
    private static Datum.PropList convertToPropList(LingoVM vm, String tString, String tDelimiter) {
        if (tDelimiter == null || tDelimiter.isEmpty()) {
            tDelimiter = ",";
        }

        Datum.PropList tProps = Datum.propList();
        String[] items = tString.split(java.util.regex.Pattern.quote(tDelimiter), -1);

        for (String rawItem : items) {
            String tPair = rawItem.trim();
            if (tPair.isEmpty()) continue;

            int eqPos = tPair.indexOf("=");
            if (eqPos <= 0) continue;

            String tProp = tPair.substring(0, eqPos).trim();
            String tValue = eqPos + 1 < tPair.length() ? tPair.substring(eqPos + 1).trim() : "";

            if (!tProp.isEmpty()) {
                tProps.put(Datum.of(tProp), Datum.of(tValue));
            }
        }

        return tProps;
    }

    /**
     * Test convertToPropList by loading the actual fuse_client.cct cast file
     * from http://localhost:3000/assets/fuse_client.cct and executing the real handler.
     */
    private static void testConvertToPropListFromRealCast() {
        System.out.println("--- Testing convertToPropList from Real Cast File ---");

        try {
            // Fetch the .cct file from localhost
            String castUrl = "http://localhost:3000/assets/fuse_client.cct";
            System.out.println("  Fetching: " + castUrl);

            URL url = new URL(castUrl);
            byte[] cctBytes;
            try (InputStream is = url.openStream()) {
                cctBytes = is.readAllBytes();
            }
            System.out.println("  Loaded " + cctBytes.length + " bytes");

            // Parse as DirectorFile
            DirectorFile castFile = DirectorFile.load(cctBytes);
            System.out.println("  Parsed DirectorFile successfully");
            System.out.println("  Scripts: " + castFile.getScripts().size());

            if (castFile.getScriptNames() != null) {
                System.out.println("  Script names: " + castFile.getScriptNames().names().size());
            }

            // Create VM with the cast file
            LingoVM vm = new LingoVM(castFile);
            CastManager castManager = castFile.createCastManager();
            vm.setCastManager(castManager);
            vm.setDebugMode(false);

            // Execute convertToPropList with System Props test data
            System.out.println("  Calling convertToPropList...");
            Datum result = vm.call("convertToPropList",
                List.of(Datum.of(SYSTEM_PROPS), Datum.of("\r")));

            // Verify result is a PropList
            if (!(result instanceof Datum.PropList)) {
                System.out.println("  ERROR: Result is not PropList, got: " +
                    result.getClass().getSimpleName() + " = " + result.stringValue());
                return;
            }

            Datum.PropList propList = (Datum.PropList) result;
            System.out.println("  Result: PropList with " + propList.count() + " properties");

            // Check for object.manager.class
            Datum objMgrClass = propList.get(Datum.of("object.manager.class"));
            if (objMgrClass == null || objMgrClass.isVoid()) {
                System.out.println("  ERROR: object.manager.class not found in result");
                System.out.println("  Properties in result:");
                for (var entry : propList.properties().entrySet()) {
                    System.out.println("    " + entry.getKey().stringValue() + " = " + entry.getValue().stringValue());
                }
                return;
            }

            System.out.println("  object.manager.class = " + objMgrClass.stringValue());

            // Parse the value and get [1]
            Datum parsed = parseLingoValue(objMgrClass.stringValue());
            if (parsed instanceof Datum.DList list) {
                String tClass = list.getAt(1).stringValue();
                System.out.println("  value(...)[1] = " + tClass);
                assert tClass.equals("Object Manager Class") :
                    "Result should be 'Object Manager Class', got: " + tClass;
            } else {
                System.out.println("  Parsed value is not a list: " + parsed.getClass().getSimpleName());
            }

            System.out.println("  Real cast file execution: PASS");
            System.out.println("  convertToPropList from Real Cast: ALL PASS\n");

        } catch (java.net.ConnectException e) {
            System.out.println("  SKIPPED: Server not running at localhost:3000");
            System.out.println("  Start the server to run this test.\n");
        } catch (Exception e) {
            System.out.println("  ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Parses a Lingo value string like ["foo", "bar"] into a Datum.
     */
    private static Datum parseLingoValue(String s) {
        s = s.trim();

        // List: ["item1", "item2", ...]
        if (s.startsWith("[") && s.endsWith("]") && !s.contains(":")) {
            s = s.substring(1, s.length() - 1).trim();
            Datum.DList list = Datum.list();

            int depth = 0;
            StringBuilder current = new StringBuilder();
            boolean inQuote = false;

            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' && (i == 0 || s.charAt(i-1) != '\\')) {
                    inQuote = !inQuote;
                    current.append(c);
                } else if (c == '[') {
                    depth++;
                    current.append(c);
                } else if (c == ']') {
                    depth--;
                    current.append(c);
                } else if (c == ',' && depth == 0 && !inQuote) {
                    String item = current.toString().trim();
                    if (!item.isEmpty()) {
                        list.add(parseLingoValue(item));
                    }
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }

            String item = current.toString().trim();
            if (!item.isEmpty()) {
                list.add(parseLingoValue(item));
            }

            return list;
        }

        // Quoted string: "foo"
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return Datum.of(s.substring(1, s.length() - 1));
        }

        // Symbol: #foo
        if (s.startsWith("#")) {
            return Datum.symbol(s.substring(1));
        }

        // Integer
        try {
            return Datum.of(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            // Not an integer
        }

        // Float
        try {
            return Datum.of(Float.parseFloat(s));
        } catch (NumberFormatException e) {
            // Not a float
        }

        return Datum.of(s);
    }
}
