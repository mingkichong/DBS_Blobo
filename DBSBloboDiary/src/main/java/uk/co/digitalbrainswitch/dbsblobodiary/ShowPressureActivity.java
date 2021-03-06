package uk.co.digitalbrainswitch.dbsblobodiary;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.app.Activity;
import android.os.Environment;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.chart.PointStyle;
import org.achartengine.model.TimeSeries;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.renderer.BasicStroke;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;
import org.achartengine.tools.PanListener;
import org.achartengine.tools.ZoomEvent;
import org.achartengine.tools.ZoomListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;

import uk.co.digitalbrainswitch.dbsblobodiary.location.TimeLocation;

public class ShowPressureActivity extends Activity implements View.OnClickListener, PanListener, ZoomListener {

    Typeface font;
    String selectedFileName = "NULL";
    // chart container
    private LinearLayout layout;
    private GraphicalView mChartView = null;
    XYMultipleSeriesRenderer renderer;

    TreeMap<Long, Integer> data;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.show_pressure);

        font = ((MyApplication) getApplication()).getCustomTypeface();

        //get selected file name from intent extra bundle
        Bundle extra = getIntent().getExtras();
        selectedFileName = extra.getString(getString(R.string.intent_extra_selected_file_name));

        //set up UI
        TextView txt = (TextView) findViewById(R.id.tvShowPressureTimeDate);
        txt.setTypeface(font);
        txt.append(translateFileNameToDate(selectedFileName));

        this.initialise();
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private void updateChartView() {
        if (mChartView != null) {
            layout.removeView(mChartView);
            mChartView = null;
        }

        XYMultipleSeriesDataset dateDataset = getDateDataset();
        renderer = getRenderer(dateDataset.getSeriesCount());

        if (ShowTimeDataActivity.panXAxisMin != 0 && ShowTimeDataActivity.panXAxisMax != 0) {
            renderer.setXAxisMin(ShowTimeDataActivity.panXAxisMin);
            renderer.setXAxisMax(ShowTimeDataActivity.panXAxisMax);
        }

        mChartView = ChartFactory.getTimeChartView(this, dateDataset, renderer, null);
        mChartView.addPanListener(this);
        mChartView.addZoomListener(this, true, true);
        mChartView.setOnClickListener(this);
        layout.addView(mChartView);
    }

    //fileName format YYYY-MM-DD_<day of week> to YYYY/MM/DD <day of week>
    private String translateFileNameToDate(String fileNameString) {
        StringTokenizer st = new StringTokenizer(fileNameString, ".");
        return st.nextToken().replaceAll("-", "/").replaceAll("_", " ");
    }

    private void initialise() {
        //set custom title bar http://stackoverflow.com/a/8748802
        this.getActionBar().setDisplayShowCustomEnabled(true);
        this.getActionBar().setDisplayShowTitleEnabled(false);
        LayoutInflater inflator = (LayoutInflater) this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflator.inflate(R.layout.titleview, null);
        //if you need to customize anything else about the text, do it here.
        //I'm using a custom TextView with a custom font in my layout xml so all I need to do is set title
        TextView tvTitle = (TextView) v.findViewById(R.id.title);
        tvTitle.setText(this.getTitle());
        tvTitle.setTextColor(getResources().getColor(android.R.color.white));
        tvTitle.setTypeface(font);
        //assign the view to the actionbar
        this.getActionBar().setCustomView(v);

        data = new TreeMap<Long, Integer>();

        readData();

        layout = (LinearLayout) findViewById(R.id.layoutShowPressureTimeChart);
    }

    private void readData() {
        ProgressDialog dialog = new ProgressDialog(ShowPressureActivity.this) {
            @Override
            protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                TextView tv = (TextView) this.findViewById(android.R.id.message);   //get the message body
                if (tv != null) {   // Shouldn't be null. Just to be paranoid enough.
                    tv.setTypeface(font);
                }
            }
        };
        dialog.setCancelable(true);
        TextView tvTitle = new TextView(ShowPressureActivity.this);
        tvTitle.setText("Reading data");
        tvTitle.setTypeface(font);
        tvTitle.setTextColor(getResources().getColor(R.color.dbs_blue));
        tvTitle.setPadding(30, 20, 30, 20);
        tvTitle.setTextSize(25);
        dialog.setCustomTitle(tvTitle);

        dialog.setMessage("Please wait ...");
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setProgress(0);
        dialog.setIndeterminate(false);

        new ReadDataTask(dialog).execute();
    }

    private void readDataFromFile(String fileName, ProgressDialog progress) {
        File root = Environment.getExternalStorageDirectory();
        File storedDirectory = new File(root, getString(R.string.stored_diary_values_directory));
        File file = new File(storedDirectory, fileName);
        try {
            FileInputStream inputStream = new FileInputStream(file);

            if (inputStream != null) {
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));

                int TOTAL_NUM_OF_LINES = 0;
                while (bufferedReader.readLine() != null) { //get the total number of lines in the input file
                    TOTAL_NUM_OF_LINES++;
                }
                inputStream.getChannel().position(0);   //reset inputstream to the beginning position
                bufferedReader = new BufferedReader(new InputStreamReader(inputStream));    //reset bufferedreader

                String receiveString;

                progress.setMax(TOTAL_NUM_OF_LINES);
                int LINE_NUM = 0;
                //Read every line from file. Discard pressure values that are lower than the threshold.
                while ((receiveString = bufferedReader.readLine()) != null) {
                    LINE_NUM++;
                    if (LINE_NUM % (TOTAL_NUM_OF_LINES / 100) == 0) { //update progress 100 times
                        progress.setProgress(LINE_NUM);
                    }

                    try {
                        StringTokenizer st = new StringTokenizer(receiveString, ";");

                        String timeString = st.nextToken();
                        String blobo_sensor_value_string = st.nextToken();
                        String calibration_mark_string = st.nextToken();
                        String calibration_difference_string = st.nextToken();

                        long timeInMillisecond = Long.parseLong(timeString);
                        int bloboSensorValue = Integer.parseInt(blobo_sensor_value_string);
                        int calibrationMark = Integer.parseInt(calibration_mark_string);
                        int calibrationDifference = Integer.parseInt(calibration_difference_string);

                        //only include bloboSensorValues that are above the threshold (calibration values)
                        if (bloboSensorValue > (calibrationMark + calibrationDifference)) {
                            data.put(timeInMillisecond, bloboSensorValue);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                inputStream.close();
            }
        } catch (FileNotFoundException e) {
            Log.e("login activity", "File not found: " + e.toString());
        } catch (IOException e) {
            Log.e("login activity", "Can not read file: " + e.toString());
        }
    }

    //Method for displaying a popup alert dialog
    private void showAlertMessage(String title, String Message) {
        AlertDialog.Builder popupBuilder = new AlertDialog.Builder(this);
        popupBuilder.setTitle(title);
        popupBuilder.setMessage(Message);
        popupBuilder.setPositiveButton("OK", null);
        popupBuilder.show();
    }


    private XYMultipleSeriesRenderer getRenderer(int numOfSeries) {
        XYMultipleSeriesRenderer renderer = new XYMultipleSeriesRenderer();
        //renderer.setBackgroundColor(Color.BLACK);

        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);

        renderer.setTextTypeface(font);
        renderer.setAxisTitleTextSize(20);
        renderer.setChartTitleTextSize(20);
//        renderer.setLabelsTextSize(25);
//        renderer.setLegendTextSize(20);
        renderer.setShowLegend(false);
//        renderer.setZoomInLimitX(1);
        renderer.setLabelsTextSize(0.035F * ((size.x < size.y) ? size.x : size.y));

        renderer.setPointSize(1f);
//        renderer.setPointSize(50f);
//        renderer.setYAxisMax(1.5f);
//        renderer.setYAxisMin(0.5f);
        renderer.setZoomEnabled(true, false);
        renderer.setZoomButtonsVisible(true);

        renderer.setPanEnabled(true, false); //Enable panning for X axis, but not Y axis
        renderer.setClickEnabled(true); //Make chart points clickable
        renderer.setMargins(new int[]{100, 20, 150, 10}); //top, left, bottom, right

        XYSeriesRenderer r;

//        final int COLORS[] = {Color.rgb(0, 191, 255), Color.rgb(30, 144, 255), Color.rgb(75, 75, 187)};
//        final int DBS_BLUE_COLOR = Color.rgb(19, 164, 210); //DBS Blue rgb(19, 164, 210)
        for (int i = 0; i < numOfSeries; i++) {
            r = new XYSeriesRenderer();
//            r.setColor(COLORS[i % COLORS.length]);
            r.setColor(Color.rgb(0, 0, 128));
            r.setPointStyle(PointStyle.POINT);
//            r.setStroke(BasicStroke.SOLID);
//            r.setFillPoints(true);
            r.setPointStrokeWidth(0.015F * ((size.x < size.y) ? size.x : size.y));
            r.setAnnotationsColor(Color.DKGRAY);
            r.setAnnotationsTextSize(0.03F * ((size.x < size.y) ? size.x : size.y));
            r.setAnnotationsTextAlign(Paint.Align.CENTER);
//            XYSeriesRenderer.FillOutsideLine fill = new XYSeriesRenderer.FillOutsideLine(XYSeriesRenderer.FillOutsideLine.Type.BELOW);
//            fill.setColor(Color.rgb(0, 0, 128));
//            r.addFillOutsideLine(fill);
            renderer.addSeriesRenderer(r);
        }

        renderer.setAxesColor(Color.DKGRAY);
        renderer.setLabelsColor(Color.BLACK);
        renderer.setShowGridY(true);
        renderer.setGridColor(Color.LTGRAY);
        renderer.setApplyBackgroundColor(true);
        renderer.setBackgroundColor(Color.WHITE);
        renderer.setMarginsColor(Color.WHITE);
        renderer.setYLabels(0);

        renderer.setPanLimits(ShowTimeDataActivity.PAN_LIMITS);

        return renderer;
    }

    private XYMultipleSeriesDataset getDateDataset() {
        XYMultipleSeriesDataset dataset = new XYMultipleSeriesDataset();
        TimeSeries series = new TimeSeries("Sensor Data");

        Set<Long> keySet = data.keySet();
        Long[] keys = keySet.toArray(new Long[keySet.size()]);

//        Display display = getWindowManager().getDefaultDisplay();
//        Point size = new Point();
//        display.getSize(size);
//        final float POINT_SIZE = 0.04F * ((size.x < size.y) ? size.x : size.y);
//        double ANNOTATION_Y_OFFSET = 0.02F * ((size.x < size.y) ? size.x : size.y);

//        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a");
//        int COUNTER = 0;
        Date prevDate = null;

//        float Y_OFFSET = 0;
//        int Y_OFFSET_SERIES_COUNTER = 0;

        for (long key : keys) {
            Date date = new Date(key);
            if (prevDate != null) {
                if ((date.getTime() - prevDate.getTime()) < 600000) { //if dates are within 10min of each other
//                    if (Y_OFFSET_SERIES_COUNTER < 0) {
//                        Y_OFFSET += 3 / (POINT_SIZE);
//                        Y_OFFSET_SERIES_COUNTER++;
//                    }
                } else {
                    //reset the Y_OFFSET
//                    Y_OFFSET = 0;
//                    Y_OFFSET_SERIES_COUNTER = 0;
                    dataset.addSeries(series);
                    series = new TimeSeries("Sensor Data");
                }
            }
            series.add(date, data.get(key));
            //add time string as annotation
//            series.addAnnotation(sdf.format(date), date.getTime(), 1 + Y_OFFSET + 1.5 / ANNOTATION_Y_OFFSET * ((COUNTER % 2 == 0) ? 1 : -1 * 1.5));
//            COUNTER++;
            prevDate = date;
        }
        dataset.addSeries(series);

        return dataset;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.show_pressure, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        super.onOptionsItemSelected(item);

        switch (item.getItemId()) {
            case R.id.menu_show_time_line:
                this.onBackPressed();
                break;
        }
        return true;
    }

    @Override
    public void onClick(View v) {

    }

    @Override
    public void panApplied() {
        ShowTimeDataActivity.panXAxisMin = renderer.getXAxisMin();
        ShowTimeDataActivity.panXAxisMax = renderer.getXAxisMax();
    }

    @Override
    public void zoomApplied(ZoomEvent zoomEvent) {
        ShowTimeDataActivity.panXAxisMin = renderer.getXAxisMin();
        ShowTimeDataActivity.panXAxisMax = renderer.getXAxisMax();
    }

    @Override
    public void zoomReset() {
//        renderer.setXAxisMin(ShowTimeDataActivity.initPanXAxisMin);
//        renderer.setXAxisMax(ShowTimeDataActivity.initPanXAxisMax);
        renderer.setXAxisMin(renderer.getPanLimits()[0]);
        renderer.setXAxisMax(renderer.getPanLimits()[1]);
        ShowTimeDataActivity.panXAxisMin = renderer.getXAxisMin();
        ShowTimeDataActivity.panXAxisMax = renderer.getXAxisMax();
    }

    public class ReadDataTask extends AsyncTask<Void, Void, Void> {
        ProgressDialog progress;

        public ReadDataTask(ProgressDialog progress) {
            this.progress = progress;
        }

        public void onPreExecute() {
            progress.show();
        }

        @Override
        protected Void doInBackground(Void... params) {
            readDataFromFile(selectedFileName, this.progress);
            return null;
        }

        public void onPostExecute(Void unused) {
            updateChartView();
            progress.dismiss();
            if (data.size() == 0) {
                showAlertMessage("Error", "Error reading data from file: " + selectedFileName);
            }
        }
    }
}
