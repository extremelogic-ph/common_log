package ph.extremelogic.common.core.log.appender;

import org.junit.jupiter.api.*;
import ph.extremelogic.common.core.log.*;
import ph.extremelogic.common.core.log.api.Level;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PackageFilterAppender Unit Tests")
class PackageFilterAppenderTest {

    private ByteArrayOutputStream out;
    private PrintStream originalOut;

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }
    @Test
    @DisplayName("PackageFilterAppender – should respect include/exclude patterns via LogManager")
    void testPackageFilterAppenderViaLogManager() {
        // Arrange
        ConsoleAppender console = new ConsoleAppender("console");
        PackageFilterAppender filter = new PackageFilterAppender(
                "package-filter",
                console,
                "com.example.*",           // include
                "!com.example.internal.*"  // exclude
        );

        Configuration configuration = new Configuration(Level.INFO);
        configuration.addAppender(filter);
        LogManager.setDefaultConfiguration(configuration);

        Logger allowedLogger = LogManager.getLogger("com.example.service.MyService");
        Logger blockedLogger  = LogManager.getLogger("com.example.internal.Secret");
        Logger ignoredLogger  = LogManager.getLogger("org.other.Stuff");

        // Act
        allowedLogger.info("allowed");
        blockedLogger.info("blocked");
        ignoredLogger.info("ignored");

        // Assert
        String output = out.toString();

        assertTrue(output.contains("allowed"),
                "Should log message from included package");
        assertFalse(output.contains("blocked"),
                "Should NOT log message from excluded package");
        assertFalse(output.contains("ignored"),
                "Should NOT log message outside include pattern");
    }
}
