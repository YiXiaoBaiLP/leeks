import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionToolbarPosition;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.table.JBTable;
import handler.TianTianFundHandler;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import quartz.HandlerJob;
import quartz.QuartzManager;
import utils.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.net.MalformedURLException;
import java.util.List;
import java.util.*;

public class FundWindow implements ToolWindowFactory {
    public static final String NAME = "Fund";
    private JPanel mPanel;

    static TianTianFundHandler fundRefreshHandler;

    private StockWindow stockWindow = new StockWindow();
    private CoinWindow coinWindow = new CoinWindow();

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        //先加载代理
        loadProxySetting();

        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(mPanel, NAME, false);
        //股票
        Content content_stock = contentFactory.createContent(stockWindow.getmPanel(), StockWindow.NAME, false);
        //虚拟货币
        Content content_coin = contentFactory.createContent(coinWindow.getmPanel(), CoinWindow.NAME, false);
        ContentManager contentManager = toolWindow.getContentManager();
        contentManager.addContent(content);
        contentManager.addContent(content_stock);
        contentManager.addContent(content_coin);
        if (StringUtils.isEmpty(PropertiesComponent.getInstance().getValue("key_funds"))) {
            // 没有配置基金数据，选择展示股票
            contentManager.setSelectedContent(content_stock);
        }
        LogUtil.setProject(project);
//        ((ToolWindowManagerEx) ToolWindowManager.getInstance(project)).addToolWindowManagerListener(new ToolWindowManagerListener() {
//            @Override
//            public void stateChanged() {
//                if (toolWindow.isVisible()){
//                    fundRefreshHandler.handle(loadFunds());
//                }
//            }
//        });
    }

    private void loadProxySetting() {
        String proxyStr = PropertiesComponent.getInstance().getValue("key_proxy");
        HttpClientPool.getHttpClient().buildHttpClient(proxyStr);
    }

    @Override
    public void init(ToolWindow window) {
        // 重要：由于idea项目窗口可多个，导致FundWindow#init方法被多次调用，出现UI和逻辑错误(bug #53)，故加此判断解决
        if (Objects.nonNull(fundRefreshHandler)) {
            LogUtil.info("Leeks UI已初始化");
            return;
        }

        JLabel refreshTimeLabel = new JLabel();
        refreshTimeLabel.setToolTipText("最后刷新时间");
        refreshTimeLabel.setBorder(new EmptyBorder(0, 0, 0, 5));
        JBTable table = new JBTable();
        //记录列名的变化
        table.getTableHeader().addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                StringBuilder tableHeadChange = new StringBuilder();
                for (int i = 0; i < table.getColumnCount(); i++) {
                    tableHeadChange.append(table.getColumnName(i)).append(",");
                }
                PropertiesComponent instance = PropertiesComponent.getInstance();
                //将列名的修改放入环境中 key:fund_table_header_key
                instance.setValue(WindowUtils.FUND_TABLE_HEADER_KEY, tableHeadChange
                        .substring(0, tableHeadChange.length() > 0 ? tableHeadChange.length() - 1 : 0));

                //LogUtil.info(instance.getValue(WindowUtils.FUND_TABLE_HEADER_KEY));
            }

        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (table.getSelectedRow() < 0)
                    return;
                String code = String.valueOf(table.getModel().getValueAt(table.convertRowIndexToModel(table.getSelectedRow()), fundRefreshHandler.codeColumnIndex));//FIX 移动列导致的BUG
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() > 1) {
                    // 鼠标左键双击
                    try {
                        PopupsUiUtil.showImageByFundCode(code, PopupsUiUtil.FundShowType.gsz, new Point(e.getXOnScreen(), e.getYOnScreen()));
                    } catch (MalformedURLException ex) {
                        ex.printStackTrace();
                        LogUtil.info(ex.getMessage());
                    }
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    //鼠标右键
                    JBPopupFactory.getInstance().createListPopup(new BaseListPopupStep<PopupsUiUtil.FundShowType>("",
                            PopupsUiUtil.FundShowType.values()) {
                        @Override
                        public @NotNull String getTextFor(PopupsUiUtil.FundShowType value) {
                            return value.getDesc();
                        }

                        @Override
                        public @Nullable PopupStep onChosen(PopupsUiUtil.FundShowType selectedValue, boolean finalChoice) {
                            try {
                                PopupsUiUtil.showImageByFundCode(code, selectedValue, new Point(e.getXOnScreen(), e.getYOnScreen()));
                            } catch (MalformedURLException ex) {
                                ex.printStackTrace();
                                LogUtil.info(ex.getMessage());
                            }
                            return super.onChosen(selectedValue, finalChoice);
                        }
                    }).show(RelativePoint.fromScreen(new Point(e.getXOnScreen(), e.getYOnScreen())));
                }
            }
        });
        fundRefreshHandler = new TianTianFundHandler(table, refreshTimeLabel);
        AnActionButton refreshAction = new AnActionButton("停止刷新当前表格数据", AllIcons.Actions.Pause) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                stop();
                this.setEnabled(false);
            }
        };
        ToolbarDecorator toolbarDecorator = ToolbarDecorator.createDecorator(table)
                .addExtraAction(new AnActionButton("持续刷新当前表格数据", AllIcons.Actions.Refresh) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        refresh();
                        refreshAction.setEnabled(true);
                    }
                })
                .addExtraAction(refreshAction)
                .setToolbarPosition(ActionToolbarPosition.TOP);
        JPanel toolPanel = toolbarDecorator.createPanel();
        toolbarDecorator.getActionsPanel().add(refreshTimeLabel, BorderLayout.EAST);
        toolPanel.setBorder(new EmptyBorder(0, 0, 0, 0));
        mPanel.add(toolPanel, BorderLayout.CENTER);
        apply();
    }

    private static List<String> loadFunds() {
//        return getConfigList("key_funds", "[,，]");
        return SettingsWindow.getConfigList("key_funds");
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public boolean isDoNotActivateOnStart() {
        return true;
    }

    public static void apply() {
        if (fundRefreshHandler != null) {
            PropertiesComponent instance = PropertiesComponent.getInstance();
            fundRefreshHandler.setStriped(instance.getBoolean("key_table_striped"));
            fundRefreshHandler.clearRow();
            fundRefreshHandler.setupTable(loadFunds());
            refresh();
        }
    }

    public static void refresh() {
        if (fundRefreshHandler != null) {
            PropertiesComponent instance = PropertiesComponent.getInstance();
            boolean colorful = instance.getBoolean("key_colorful");
            fundRefreshHandler.refreshColorful(colorful);
            List<String> codes = loadFunds();
            if (CollectionUtils.isEmpty(codes)) {
                stop(); //如果没有数据则不需要启动时钟任务浪费资源
            } else {
                fundRefreshHandler.handle(codes);
                QuartzManager quartzManager = QuartzManager.getInstance(NAME); // 时钟任务
                HashMap<String, Object> dataMap = new HashMap<>();
                dataMap.put(HandlerJob.KEY_HANDLER, fundRefreshHandler);
                dataMap.put(HandlerJob.KEY_CODES, codes);
                String cronExpression = instance.getValue("key_cron_expression_fund");
                if (StringUtils.isEmpty(cronExpression)) {
                    cronExpression = "0 * * * * ?";
                }
                quartzManager.runJob(HandlerJob.class, cronExpression, dataMap);
            }
        }
    }

    public static void stop() {
        QuartzManager.getInstance(NAME).stopJob();
        if (fundRefreshHandler != null) {
            fundRefreshHandler.stopHandle();
        }
    }
}
