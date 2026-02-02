import com.libreshockwave.DirectorFile;
import java.nio.file.Files;
import java.nio.file.Path;

public class TestLoad {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Path.of("C:/SourceControl/fuse_client.cct"));
        System.out.println("Read " + data.length + " bytes");
        DirectorFile file = DirectorFile.load(data);
        System.out.println("Loaded. Scripts: " + file.getScripts().size());
    }
}
