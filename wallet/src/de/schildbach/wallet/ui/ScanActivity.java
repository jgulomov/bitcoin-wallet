/*
 * Copyright 2012-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui;

import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.Vibrator;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.ResultPointCallback;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import de.schildbach.wallet.camera.CameraManager;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceHolder.Callback, ActivityCompat.OnRequestPermissionsResultCallback
{
	public static final String INTENT_EXTRA_RESULT = "result";

	private static final long VIBRATE_DURATION = 50L;
	private static final long AUTO_FOCUS_INTERVAL_MS = 2500L;

	private final CameraManager cameraManager = new CameraManager();
	private ScannerView scannerView;
	private SurfaceView surfaceView;
	private SurfaceHolder surfaceHolder;
	private volatile boolean surfaceCreated = false;

	private Vibrator vibrator;
	private HandlerThread cameraThread;
	private volatile Handler cameraHandler;

	private static boolean DISABLE_CONTINUOUS_AUTOFOCUS = Build.MODEL.equals("GT-I9100") // Galaxy S2
			|| Build.MODEL.equals("SGH-T989") // Galaxy S2
			|| Build.MODEL.equals("SGH-T989D") // Galaxy S2 X
			|| Build.MODEL.equals("SAMSUNG-SGH-I727") // Galaxy S2 Skyrocket
			|| Build.MODEL.equals("GT-I9300") // Galaxy S3
			|| Build.MODEL.equals("GT-N7000"); // Galaxy Note

	private static final Logger log = LoggerFactory.getLogger(ScanActivity.class);

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

		setContentView(R.layout.scan_activity);
		scannerView = (ScannerView) findViewById(R.id.scan_activity_mask);
		surfaceView = (SurfaceView) findViewById(R.id.scan_activity_preview);
		surfaceHolder = surfaceView.getHolder();
		surfaceHolder.addCallback(this);

		cameraThread = new HandlerThread("cameraThread", Process.THREAD_PRIORITY_BACKGROUND);
		cameraThread.start();
		cameraHandler = new Handler(cameraThread.getLooper());

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
			ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.CAMERA }, 0);
	}

	@Override
	protected void onResume()
	{
		super.onResume();

		maybeOpenCamera();
	}

	@Override
	protected void onPause()
	{
		cameraHandler.post(closeRunnable);

		super.onPause();
	}

	@Override
	protected void onDestroy()
	{
		// cancel background thread
		cameraHandler.removeCallbacksAndMessages(null);
		cameraThread.quit();

		surfaceHolder.removeCallback(this);

		super.onDestroy();
	}

	@Override
	public void onRequestPermissionsResult(final int requestCode, final String[] permissions, final int[] grantResults)
	{
		if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
			maybeOpenCamera();
		else
			WarnDialogFragment.newInstance(R.string.scan_camera_permission_dialog_title, getString(R.string.scan_camera_permission_dialog_message))
					.show(getFragmentManager(), "dialog");

	}

	private void maybeOpenCamera()
	{
		if (surfaceCreated && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
			cameraHandler.post(openRunnable);
	}

	@Override
	public void surfaceCreated(final SurfaceHolder holder)
	{
		surfaceCreated = true;
		maybeOpenCamera();
	}

	@Override
	public void surfaceDestroyed(final SurfaceHolder holder)
	{
		surfaceCreated = false;
	}

	@Override
	public void surfaceChanged(final SurfaceHolder holder, final int format, final int width, final int height)
	{
	}

	@Override
	public void onAttachedToWindow()
	{
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
	}

	@Override
	public void onBackPressed()
	{
		setResult(RESULT_CANCELED);
		finish();
	}

	@Override
	public boolean onKeyDown(final int keyCode, final KeyEvent event)
	{
		switch (keyCode)
		{
			case KeyEvent.KEYCODE_FOCUS:
			case KeyEvent.KEYCODE_CAMERA:
				// don't launch camera app
				return true;
			case KeyEvent.KEYCODE_VOLUME_DOWN:
			case KeyEvent.KEYCODE_VOLUME_UP:
				cameraHandler.post(new Runnable()
				{
					@Override
					public void run()
					{
						cameraManager.setTorch(keyCode == KeyEvent.KEYCODE_VOLUME_UP);
					}
				});
				return true;
		}

		return super.onKeyDown(keyCode, event);
	}

	public void handleResult(final Result scanResult, final Bitmap thumbnailImage, final float thumbnailScaleFactor)
	{
		vibrator.vibrate(VIBRATE_DURATION);

		// superimpose dots to highlight the key features of the qr code
		final ResultPoint[] points = scanResult.getResultPoints();
		if (points != null && points.length > 0)
		{
			final Paint paint = new Paint();
			paint.setColor(getResources().getColor(R.color.scan_result_dots));
			paint.setStrokeWidth(10.0f);

			final Canvas canvas = new Canvas(thumbnailImage);
			canvas.scale(thumbnailScaleFactor, thumbnailScaleFactor);
			for (final ResultPoint point : points)
				canvas.drawPoint(point.getX(), point.getY(), paint);
		}

		scannerView.drawResultBitmap(thumbnailImage);

		final Intent result = new Intent();
		result.putExtra(INTENT_EXTRA_RESULT, scanResult.getText());
		setResult(RESULT_OK, result);

		// delayed finish
		new Handler().post(new Runnable()
		{
			@Override
			public void run()
			{
				finish();
			}
		});
	}

	private final Runnable openRunnable = new Runnable()
	{
		@Override
		public void run()
		{
			try
			{
				final Camera camera = cameraManager.open(surfaceHolder, !DISABLE_CONTINUOUS_AUTOFOCUS);

				final Rect framingRect = cameraManager.getFrame();
				final Rect framingRectInPreview = cameraManager.getFramePreview();

				runOnUiThread(new Runnable()
				{
					@Override
					public void run()
					{
						scannerView.setFraming(framingRect, framingRectInPreview);
					}
				});

				final String focusMode = camera.getParameters().getFocusMode();
				final boolean nonContinuousAutoFocus = Camera.Parameters.FOCUS_MODE_AUTO.equals(focusMode)
						|| Camera.Parameters.FOCUS_MODE_MACRO.equals(focusMode);

				if (nonContinuousAutoFocus)
					cameraHandler.post(new AutoFocusRunnable(camera));

				cameraHandler.post(fetchAndDecodeRunnable);
			}
			catch (final Exception x)
			{
				log.info("problem opening camera", x);
				runOnUiThread(new Runnable()
				{
					@Override
					public void run()
					{
						if (!isFinishing())
							WarnDialogFragment
									.newInstance(R.string.scan_camera_problem_dialog_title, getString(R.string.scan_camera_problem_dialog_message))
									.show(getFragmentManager(), "dialog");
					}
				});
			}
		}
	};

	private final Runnable closeRunnable = new Runnable()
	{
		@Override
		public void run()
		{
			cameraHandler.removeCallbacksAndMessages(null);
			cameraManager.close();
		}
	};

	private final class AutoFocusRunnable implements Runnable
	{
		private final Camera camera;

		public AutoFocusRunnable(final Camera camera)
		{
			this.camera = camera;
		}

		@Override
		public void run()
		{
			camera.autoFocus(new Camera.AutoFocusCallback()
			{
				@Override
				public void onAutoFocus(final boolean success, final Camera camera)
				{
					// schedule again
					cameraHandler.postDelayed(AutoFocusRunnable.this, AUTO_FOCUS_INTERVAL_MS);
				}
			});
		}
	}

	private final Runnable fetchAndDecodeRunnable = new Runnable()
	{
		private final QRCodeReader reader = new QRCodeReader();
		private final Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);

		@Override
		public void run()
		{
			cameraManager.requestPreviewFrame(new PreviewCallback()
			{
				@Override
				public void onPreviewFrame(final byte[] data, final Camera camera)
				{
					decode(data);
				}
			});
		}

		private void decode(final byte[] data)
		{
			final PlanarYUVLuminanceSource source = cameraManager.buildLuminanceSource(data);
			final BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

			try
			{
				hints.put(DecodeHintType.NEED_RESULT_POINT_CALLBACK, new ResultPointCallback()
				{
					@Override
					public void foundPossibleResultPoint(final ResultPoint dot)
					{
						runOnUiThread(new Runnable()
						{
							@Override
							public void run()
							{
								scannerView.addDot(dot);
							}
						});
					}
				});
				final Result scanResult = reader.decode(bitmap, hints);

				final int thumbnailWidth = source.getThumbnailWidth();
				final int thumbnailHeight = source.getThumbnailHeight();
				final float thumbnailScaleFactor = (float) thumbnailWidth / source.getWidth();

				final Bitmap thumbnailImage = Bitmap.createBitmap(thumbnailWidth, thumbnailHeight, Bitmap.Config.ARGB_8888);
				thumbnailImage.setPixels(source.renderThumbnail(), 0, thumbnailWidth, 0, 0, thumbnailWidth, thumbnailHeight);

				runOnUiThread(new Runnable()
				{
					@Override
					public void run()
					{
						handleResult(scanResult, thumbnailImage, thumbnailScaleFactor);
					}
				});
			}
			catch (final ReaderException x)
			{
				// retry
				cameraHandler.post(fetchAndDecodeRunnable);
			}
			finally
			{
				reader.reset();
			}
		}
	};

	public static class WarnDialogFragment extends DialogFragment
	{
		public static WarnDialogFragment newInstance(final int titleResId, final String message)
		{
			final WarnDialogFragment fragment = new WarnDialogFragment();
			final Bundle args = new Bundle();
			args.putInt("title", titleResId);
			args.putString("message", message);
			fragment.setArguments(args);
			return fragment;
		}

		@Override
		public Dialog onCreateDialog(final Bundle savedInstanceState)
		{
			final Bundle args = getArguments();
			final DialogBuilder dialog = DialogBuilder.warn(getActivity(), args.getInt("title"));
			dialog.setMessage(args.getString("message"));
			dialog.singleDismissButton(new OnClickListener()
			{
				@Override
				public void onClick(final DialogInterface dialog, final int which)
				{
					getActivity().finish();
				}
			});
			return dialog.create();
		}

		@Override
		public void onCancel(final DialogInterface dialog)
		{
			getActivity().finish();
		}
	}
}
