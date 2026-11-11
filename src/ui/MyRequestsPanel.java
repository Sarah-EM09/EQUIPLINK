package src.ui;

import src.model.BorrowRequest;
import src.controller.BorrowRequestController;
import src.ui.components.PrimaryButton;
import src.ui.components.SurfacePanel;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public class MyRequestsPanel extends JPanel {

    private final long borrowerId;
    private final BorrowRequestController requestController;
    private final Consumer<BorrowRequest> editHandler;
    private final Runnable changedHandler;
    private final JPanel requestCards = new JPanel();
    private final JTextField search = new JTextField(22);
    private final src.ui.components.DateFilter dateFilter = new src.ui.components.DateFilter();
    private final JComboBox<String> dateSort = new JComboBox<>(
            new String[] { "Newest first", "Oldest first" });
    private final String statusFilter;
    private List<BorrowRequest> requests = List.of();

    public MyRequestsPanel(
            long borrowerId,
            BorrowRequestController requestController,
            Consumer<BorrowRequest> editHandler,
            Runnable changedHandler) {
        this(borrowerId, requestController, editHandler, changedHandler, "ALL");
    }

    public MyRequestsPanel(
            long borrowerId,
            BorrowRequestController requestController,
            Consumer<BorrowRequest> editHandler,
            Runnable changedHandler,
            String statusFilter) {
        this.borrowerId = borrowerId;
        this.requestController = requestController;
        this.editHandler = editHandler;
        this.changedHandler = changedHandler;
        this.statusFilter = statusFilter == null ? "ALL" : statusFilter;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(AppTheme.background());
        setBorder(new EmptyBorder(2, 2, 22, 2));
        JLabel heading = new JLabel("Your active requests");
        heading.setFont(AppTheme.semibold(18));
        heading.setForeground(AppTheme.foreground());
        add(heading);
        add(Box.createVerticalStrut(12));

        JPanel filters = new JPanel(new BorderLayout(8, 0));
        filters.setOpaque(false);
        UiSupport.styleInput(search);
        search.putClientProperty("JTextField.placeholderText", "Search by code, venue, or equipment");
        search.getDocument().addDocumentListener(UiSupport.documentListener(this::showRequests));
        filters.add(search, BorderLayout.CENTER);
        dateSort.removeAllItems();
        dateSort.addItem("Event date (soonest)");
        dateSort.addItem("Event date (latest)");
        dateSort.addItem("Newest submitted");
        dateSort.addItem("Oldest submitted");
        dateSort.setFont(AppTheme.regular(12));
        dateSort.setPreferredSize(new java.awt.Dimension(180, 40));
        dateSort.addActionListener(event -> showRequests());
        dateFilter.addChangeListener(this::showRequests);
        JPanel tools = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        tools.setOpaque(false);
        tools.add(dateFilter);
        tools.add(dateSort);
        filters.add(tools, BorderLayout.EAST);
        add(filters);
        add(Box.createVerticalStrut(12));

        requestCards.setOpaque(false);
        requestCards.setLayout(new BoxLayout(requestCards, BoxLayout.Y_AXIS));
        add(requestCards);
        reload();
    }

    public void reload() {
        requestCards.removeAll();
        requestCards.add(messageCard("Loading your requests…", false));
        requestCards.revalidate();
        requestCards.repaint();

        new SwingWorker<List<BorrowRequest>, Void>() {
            @Override
            protected List<BorrowRequest> doInBackground() throws Exception {
                return requestController.findActiveRequests(borrowerId);
            }

            @Override
            protected void done() {
                try {
                    requests = get();
                    showRequests();
                } catch (Exception exception) {
                    String message = exception.getCause() == null
                            ? exception.getMessage()
                            : exception.getCause().getMessage();
                    showError("Unable to load your requests: " + message);
                }
            }
        }.execute();
    }

    private void showRequests() {
        requestCards.removeAll();
        String query = search.getText().trim().toLowerCase();
        List<BorrowRequest> filtered = new ArrayList<>();
        for (BorrowRequest request : requests) {
            if (matchesFilter(request) && dateFilter.matches(request.getEventDate())
                    && searchableText(request).contains(query)) {
                filtered.add(request);
            }
        }
        Comparator<BorrowRequest> cmp;
        switch (dateSort.getSelectedIndex()) {
            case 1:
                cmp = Comparator.comparing(BorrowRequest::getEventDate,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed();
                break;
            case 2:
                cmp = Comparator.comparing(BorrowRequest::getSubmittedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed();
                break;
            case 3:
                cmp = Comparator.comparing(BorrowRequest::getSubmittedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case 0:
            default:
                cmp = Comparator.comparing(BorrowRequest::getEventDate,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                break;
        }
        filtered.sort(cmp.thenComparing(Comparator.comparing(BorrowRequest::getStartTime,
                Comparator.nullsLast(Comparator.naturalOrder()))));
        if (filtered.isEmpty()) {
            requestCards.add(messageCard(
                    requests.isEmpty()
                            ? "No pending or borrowed request yet. Start by requesting equipment."
                            : "No requests match the current search or filter.",
                    false));
        } else {
            for (BorrowRequest request : filtered) {
                requestCards.add(requestCard(request));
                requestCards.add(Box.createVerticalStrut(10));
            }
        }
        requestCards.revalidate();
        requestCards.repaint();
    }

    private boolean matchesFilter(BorrowRequest request) {
        if ("PENDING".equals(statusFilter)) {
            return "PENDING_STAFF".equals(request.getStatus())
                    || "NEEDS_ACTION".equals(request.getStatus())
                    || "PENDING_ADMIN".equals(request.getStatus())
                    || "PENDING_DIRECTOR".equals(request.getStatus())
                    || "APPROVED".equals(request.getStatus());
        }
        return !"BORROWED".equals(statusFilter)
                || "RELEASED".equals(request.getStatus());
    }

    private String searchableText(BorrowRequest request) {
        StringBuilder text = new StringBuilder()
                .append(request.getRequestCode()).append(' ')
                .append(request.getVenue()).append(' ')
                .append(request.getPurpose()).append(' ')
                .append(request.getStatus());
        for (var item : request.getEquipmentItems()) {
            text.append(' ').append(item.getEquipmentName())
                    .append(' ').append(item.getEquipmentCode());
        }
        return text.toString().toLowerCase();
    }

    private JPanel requestCard(BorrowRequest request) {
        final float[] hover = { 0f };
        SurfacePanel card = new SurfacePanel() {
            @Override
            protected void paintBorder(java.awt.Graphics graphics) {
                super.paintBorder(graphics);
                if (hover[0] <= 0f)
                    return;
                java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(AppTheme.alpha(new java.awt.Color(0, 0, 128), Math.round(255 * hover[0])));
                g.setStroke(new java.awt.BasicStroke(2f));
                int m = SurfacePanel.MARGIN;
                g.drawRoundRect(m, m, getWidth() - m * 2 - 1, getHeight() - m * 2 - 1, 18, 18);
                g.dispose();
            }
        };
        javax.swing.Timer hoverTimer = new javax.swing.Timer(15, null);
        hoverTimer.addActionListener(event -> {
            boolean inside = card.getMousePosition() != null;
            float target = inside ? 1f : 0f, step = 15f / 200f;
            if (Math.abs(hover[0] - target) <= step) {
                hover[0] = target;
                hoverTimer.stop();
            } else {
                hover[0] += hover[0] < target ? step : -step;
            }
            card.repaint();
        });
        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                if (!hoverTimer.isRunning())
                    hoverTimer.start();
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                if (!hoverTimer.isRunning())
                    hoverTimer.start();
            }
        });
        card.setLayout(new BorderLayout(15, 7));
        JPanel details = new JPanel();
        details.setOpaque(false);
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));

        JPanel titleRow = new JPanel(new BorderLayout(10, 0));
        titleRow.setOpaque(false);
        JLabel code = new JLabel(request.getRequestCode());
        code.setFont(AppTheme.semibold(14));
        code.setForeground(AppTheme.foreground());
        JLabel status = new src.ui.components.StatusBadge(request.getStatus());
        status.setHorizontalAlignment(SwingConstants.RIGHT);
        titleRow.add(code, BorderLayout.WEST);
        titleRow.add(status, BorderLayout.EAST);

        String itemSummary = request.getEquipmentItems().isEmpty()
                ? "No equipment items"
                : request.getEquipmentItems().get(0).getEquipmentName()
                        + (request.getEquipmentItems().size() > 1
                                ? " +" + (request.getEquipmentItems().size() - 1) + " more"
                                : "");
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.ENGLISH);
        String when = request.getEventDate() == null ? "" : request.getEventDate().format(
                DateTimeFormatter.ofPattern("MMM d, yyyy"));
        if (request.getStartTime() != null && request.getEndTime() != null) {
            when += "  " + request.getStartTime().format(tf) + "–" + request.getEndTime().format(tf);
        }
        JLabel subtitle = new JLabel("<html><div style='width:520px'>" + escape(itemSummary)
                + "  ·  " + escape(request.getVenue()) + "<br><b>Schedule:</b> " + escape(when)
                + "  ·  <b>Status:</b> " + escape(src.ui.UiSupport.displayStatus(request.getStatus()))
                + "</div></html>");
        subtitle.setFont(AppTheme.regular(11));
        subtitle.setForeground(AppTheme.muted());
        details.add(titleRow);
        details.add(Box.createVerticalStrut(7));
        details.add(subtitle);
        card.add(details, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        actions.setOpaque(false);
        PrimaryButton view = new PrimaryButton("View details", PrimaryButton.Style.SECONDARY);
        view.addActionListener(event -> showDetails(request));
        actions.add(view);

        if (isEditable(request)) {
            PrimaryButton edit = new PrimaryButton("Edit", PrimaryButton.Style.SECONDARY);
            edit.addActionListener(event -> editHandler.accept(request));
            actions.add(edit);
            PrimaryButton delete = new PrimaryButton("Delete", PrimaryButton.Style.DANGER);
            delete.addActionListener(event -> deleteRequest(request));
            actions.add(delete);
        }
        card.add(actions, BorderLayout.SOUTH);
        return card;
    }

    private boolean isEditable(BorrowRequest request) {
        if ("PENDING_STAFF".equals(request.getStatus()) || "NEEDS_ACTION".equals(request.getStatus())) {
            return true;
        }
        // allow edit/cancel while no provider has assigned any equipment group yet
        if (request.getEquipmentItems() == null || request.getEquipmentItems().isEmpty()) {
            return "PENDING_ADMIN".equals(request.getStatus());
        }
        boolean noneAssigned = request.getEquipmentItems().stream()
                .allMatch(i -> i.getAssignedQuantity() <= 0
                        && (i.getGroupStatus() == null || "PENDING".equals(i.getGroupStatus())
                                || "UNABLE".equals(i.getGroupStatus()) || "CANCELLED".equals(i.getGroupStatus())));
        return noneAssigned && !"APPROVED".equals(request.getStatus())
                && !"RELEASED".equals(request.getStatus()) && !"COMPLETED".equals(request.getStatus())
                && !"REJECTED".equals(request.getStatus()) && !"CANCELLED".equals(request.getStatus());
    }

    private void showDetails(BorrowRequest request) {
        Window owner = javax.swing.SwingUtilities.getWindowAncestor(this);
        new RequestDetailsDialog(owner, request).setVisible(true);
    }

    private void deleteRequest(BorrowRequest request) {
        int answer = JOptionPane.showConfirmDialog(
                this,
                "Remove " + request.getRequestCode()
                        + " from your active requests? It will be recorded as cancelled in your history.",
                "Delete request",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                requestController.deleteRequest(borrowerId, request.getRequestId());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    changedHandler.run();
                } catch (Exception exception) {
                    String message = exception.getCause() == null
                            ? exception.getMessage()
                            : exception.getCause().getMessage();
                    JOptionPane.showMessageDialog(
                            MyRequestsPanel.this, message, "Unable to delete request",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private JPanel messageCard(String text, boolean error) {
        SurfacePanel card = new SurfacePanel();
        card.setLayout(new BorderLayout());
        JLabel label = new JLabel(text);
        label.setFont(AppTheme.regular(12));
        label.setForeground(error ? AppTheme.danger() : AppTheme.muted());
        card.add(label, BorderLayout.CENTER);
        return card;
    }

    private void showError(String message) {
        requestCards.removeAll();
        requestCards.add(messageCard(message, true));
        requestCards.revalidate();
        requestCards.repaint();
    }

    private String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
