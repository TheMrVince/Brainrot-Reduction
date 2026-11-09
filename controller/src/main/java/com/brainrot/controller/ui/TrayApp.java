package com.brainrot.controller.ui;

import com.brainrot.controller.config.Config;
import com.brainrot.controller.policy.EmergencyUnlockResult;
import com.brainrot.controller.service.ControllerService;
import com.brainrot.controller.service.StatusSnapshot;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;

/**
 * The menu-bar icon and its menu. Deliberately thin: what it says comes from {@link StatusPresenter},
 * what it does comes from {@link ControllerService}. All methods that touch AWT run on the event thread.
 */
public final class TrayApp {
    private static final String TITLE = "Brainrot Reduction";
    private static final int ICON_SIZE = 44;  // 22 pt at 2x; the tray scales it to fit

    private final ControllerService service;
    private final Config config;
    private final Runnable onQuit;
    private final StatusPresenter presenter = new StatusPresenter(ZoneId.systemDefault());
    private final Image lockedIcon = TrayIcons.padlock(true, ICON_SIZE);
    private final Image unlockedIcon = TrayIcons.padlock(false, ICON_SIZE);

    private TrayIcon trayIcon;
    private StatusSnapshot shown;

    public TrayApp(ControllerService service, Config config, Runnable onQuit) {
        this.service = service;
        this.config = config;
        this.onQuit = onQuit;
    }

    /** Puts the icon in the menu bar. Returns false if this system has no tray. Call on the event thread. */
    public boolean install() {
        if (!SystemTray.isSupported()) return false;

        trayIcon = new TrayIcon(lockedIcon, TITLE, new PopupMenu());
        trayIcon.setImageAutoSize(true);
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            return false;
        }
        showStartingMenu();
        service.addStatusListener(status -> SwingUtilities.invokeLater(() -> show(status)));
        return true;
    }

    public void remove() {
        if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
    }

    private void show(StatusSnapshot status) {
        StatusPresenter.View view = presenter.present(status, Instant.now(), shown);
        shown = status;

        trayIcon.setImage(view.locked() ? lockedIcon : unlockedIcon);
        trayIcon.setToolTip(view.headline());
        buildMenu(view.headline(), view.details());
        if (view.notification() != null) {
            trayIcon.displayMessage(TITLE, view.notification(), TrayIcon.MessageType.INFO);
        }
    }

    private void showStartingMenu() {
        buildMenu("Starting...", java.util.List.of());
    }

    private void buildMenu(String headline, java.util.List<String> details) {
        PopupMenu menu = trayIcon.getPopupMenu();
        menu.removeAll();

        menu.add(disabled(headline));
        for (String line : details) menu.add(disabled(line));
        menu.addSeparator();

        MenuItem check = new MenuItem("Check now");
        check.addActionListener(event -> service.requestCheck());
        menu.add(check);

        MenuItem unlock = new MenuItem("Emergency unlock...");
        unlock.addActionListener(event -> promptEmergencyUnlock());
        menu.add(unlock);

        menu.addSeparator();
        MenuItem quit = new MenuItem("Quit");
        quit.addActionListener(event -> onQuit.run());
        menu.add(quit);
    }

    private static MenuItem disabled(String text) {
        MenuItem item = new MenuItem(text);
        item.setEnabled(false);
        return item;
    }

    private void promptEmergencyUnlock() {
        Config.EmergencyUnlock rules = config.emergencyUnlock();
        JTextField field = new JTextField(28);
        Object[] content = {
                "To unlock for " + rules.durationMinutes() + " minutes, type this phrase exactly:\n\n    "
                        + rules.confirmationPhrase() + "\n\nYou get " + rules.perWeek()
                        + " emergency unlock(s) in any 7 days.",
                field};

        JOptionPane pane = new JOptionPane(content, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog dialog = pane.createDialog("Emergency unlock");
        dialog.setAlwaysOnTop(true);  // this app has no window of its own to sit in front of
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                field.requestFocusInWindow();
            }
        });
        dialog.setVisible(true);

        if (!Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue())) return;
        String typed = field.getText();

        // The service may be mid-check on the network; do not freeze the menu bar waiting for it.
        CompletableFuture.supplyAsync(() -> service.requestEmergencyUnlock(typed))
                .thenAccept(result -> SwingUtilities.invokeLater(() -> reportUnlock(result)));
    }

    private void reportUnlock(EmergencyUnlockResult result) {
        // A granted unlock announces itself through the status change; only refusals need a dialog.
        if (result instanceof EmergencyUnlockResult.Granted) return;

        JOptionPane pane = new JOptionPane(presenter.describe(result, Instant.now()), JOptionPane.INFORMATION_MESSAGE);
        JDialog dialog = pane.createDialog("Emergency unlock");
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
    }
}
