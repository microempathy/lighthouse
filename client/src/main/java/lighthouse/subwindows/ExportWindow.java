package lighthouse.subwindows;

import com.google.common.collect.*;
import com.google.protobuf.*;
import de.jensd.fx.fontawesome.*;
import javafx.application.Platform;
import javafx.fxml.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.stage.*;
import lighthouse.*;
import lighthouse.files.*;
import lighthouse.protocol.*;
import lighthouse.utils.*;
import lighthouse.wallet.*;
import org.slf4j.*;

import javax.annotation.*;
import java.io.*;
import java.nio.file.*;

// TODO: The folder explainer here is actually dead code.

public class ExportWindow {
    private static final Logger log = LoggerFactory.getLogger(ExportWindow.class);

    @FXML HBox folderWatchExplainer;
    @FXML StackPane dragArea;
    @FXML Label moneyIcon;

    public Main.OverlayUI<InnerWindow> overlayUI;

    private Project project;
    @Nullable private PledgingWallet.PledgeSupplier pledge;

    public void initialize() {
        // TODO: Make a composite icon that looks more pledgey.
        AwesomeDude.setIcon(moneyIcon, AwesomeIcon.MONEY, "70.0");
        moneyIcon.setTextFill(Color.valueOf("#cccccc"));
    }

    public static void openForPledge(Project project, PledgingWallet.PledgeSupplier pledge) {
        log.info("Open ExportWindow for a pledge for {}", project.getTitle());
        ExportWindow window = Main.instance.<ExportWindow>overlayUI("subwindows/export.fxml", "Export pledge").controller;
        window.project = project;
        window.pledge = pledge;
        ((BorderPane)window.folderWatchExplainer.getParent()).setBottom(null);
    }

    public static void openForProject(Project project) {
        log.info("Open ExportWindow for saving project '{}'", project.getTitle());
        ExportWindow window = Main.instance.<ExportWindow>overlayUI("subwindows/export.fxml", "Export project").controller;
        window.project = project;
        // Don't show "will watch directory" explainer for server assisted projects.
        if (project.getPaymentURL() != null) {
            ((BorderPane)window.folderWatchExplainer.getParent()).setBottom(null);
        }
    }

    public static DataFormat PLEDGE_MIME_TYPE = new DataFormat(LHUtils.PLEDGE_MIME_TYPE);
    public static DataFormat PROJECT_MIME_TYPE = new DataFormat(LHUtils.PROJECT_MIME_TYPE);

    public static class DragData {
        public Path tempDragDirectory, tempDragFile;

        public void done() {
            log.info("Drag done!");
            // Tell the JVM to clean up at shutdown time. If we delete too early the OS notifies the drag is done but
            // the file manager is still trying to grab the file. There doesn't seem to be any way to provide a named
            // file for dragging without it already being on the filesystem.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    log.info("Deleting temp file+dir {}", tempDragFile);
                    Files.delete(tempDragFile);
                    Files.delete(tempDragDirectory);
                } catch (IOException e) {
                    log.error("Could not delete temp dir after drag", e);
                }
            }, "Temp file deleter thread"));
        }
    }

    private DragData dragData;

    @FXML
    public void dragDetected(MouseEvent event) {
        // Create a temp directory so the file can have a nice name, and then we will delete it a few seconds
        // after the drag operation has finished.
        final String fileName = getFileName();
        boolean savingPledge = pledge != null;
        Message data = savingPledge ? pledge.getData() : project.getProto();
        final DataFormat mimeType = savingPledge ? PLEDGE_MIME_TYPE : PROJECT_MIME_TYPE;
        dragData = startDrag(fileName, data, mimeType, dragArea);
    }

    public static DragData startDrag(String fileName, Message data, DataFormat mimeType, Node dragArea) {
        try {
            DragData dd = new DragData();
            dd.tempDragDirectory = Files.createTempDirectory(Main.APP_NAME);
            dd.tempDragFile = dd.tempDragDirectory.resolve(fileName);
            try (OutputStream outputStream = Files.newOutputStream(dd.tempDragFile)) {
                data.writeTo(outputStream);
            }
            Dragboard dragboard = dragArea.startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.putFilesByPath(ImmutableList.of(dd.tempDragFile.toString()));
            content.put(mimeType, data.toByteArray());
            dragboard.setContent(content);
            return dd;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getFileName() {
        if (pledge != null) {
            final String FILE_NAME = "Pledge by %s for %s%s";
            return String.format(FILE_NAME, Main.demoName == null ? System.getProperty("user.name") : Main.demoName, project.getTitle(), DiskManager.PLEDGE_FILE_EXTENSION);
        } else {
            return project.getSuggestedFileName();
        }
    }

    @FXML
    public void dragDone(DragEvent event) {
        dragData.done();
        if (pledge != null)
            pledge.commit(true);
        overlayUI.done();
    }

    @FXML
    public void saveClicked() {
        // runLater: shitty hack around RT-37821 (consider upgrading to 8u40 when available and/or applying fix locally)
        // otherwise pressing enter can cause a crash here when we open a new window with a default button
        Platform.runLater(() -> {
            boolean savingPledge = pledge != null;
            log.info("Save clicked for {}", savingPledge ? "pledge" : "project");

            FileChooser chooser = new FileChooser();
            chooser.setTitle(savingPledge ? "Save pledge to a file" : "Save project to a file");
            chooser.setInitialFileName(getFileName());
            GuiUtils.platformFiddleChooser(chooser);
            File file = chooser.showSaveDialog(Main.instance.mainStage);
            if (file == null) {
                log.info(" ... but user cancelled");
                return;
            }
            Message data = savingPledge ? pledge.getData() : project.getProto();
            log.info("Saving {} data to {}", pledge != null ? "pledge" : "project", file);
            try (OutputStream outputStream = new FileOutputStream(file)) {
                data.writeTo(outputStream);
                if (savingPledge) {
                    pledge.commit(true);
                } else if (project.getPaymentURL() != null && project.getPaymentURL().getHost().equals("vinumeris.com")) {
                    // Special last minute usability hack for this server only.
                    // TODO: either generalise this or implement issue 31 (smoother upload/submit path to servers).
                    ProjectSubmitInstructionsWindow.open("project-hosting@vinumeris.com");
                    return;
                }
                overlayUI.done();
            } catch (IOException e) {
                GuiUtils.informationalAlert("Failed to save file", e.getLocalizedMessage());
            }
        });
    }
}
