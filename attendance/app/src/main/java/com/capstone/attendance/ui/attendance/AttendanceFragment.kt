package com.capstone.attendance.ui.attendance

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.capstone.attendance.R
import com.capstone.attendance.data.remote.User
import com.capstone.attendance.databinding.FragmentAttendanceBinding
import com.capstone.attendance.utils.*
import com.google.android.gms.location.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import www.sanju.motiontoast.MotionToast
import www.sanju.motiontoast.MotionToastStyle
import java.util.*

class AttendanceFragment : Fragment() {
    private var _binding: FragmentAttendanceBinding? = null
    private lateinit var auth: FirebaseAuth
    private lateinit var locationRequest: LocationRequest
    private var fusedLocationProviderClient: FusedLocationProviderClient? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAttendanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        auth = FirebaseAuth.getInstance()
        initLocation()
        checkPermissionLocations()
        onClick()
    }

    private fun checkPermission(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return false
    }

    private fun checkPermissionLocations() {
        if (checkPermission()) {
            if (!isLocationEnabled()) {
                FunctionLibrary.toast(
                    context as Activity,
                    TOAST_WARNING,
                    PERMISSION_GPS,
                    MotionToastStyle.WARNING,
                    MotionToast.GRAVITY_BOTTOM,
                    MotionToast.LONG_DURATION,
                    ResourcesCompat.getFont(context as Activity, R.font.helveticabold)
                )
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } else {
                requestPermission()
            }
        }
    }

    private fun requestPermission() {
        ActivityCompat.requestPermissions(
            requireContext() as Activity, arrayOf(
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.INTERNET
            ),
            LOCATION_PERMISSION
        )
    }

    private fun onClick() {
        binding.fabCheckIn.setOnClickListener {
            loadScanLocation()
            Handler(Looper.getMainLooper()).postDelayed({
                getLastLocation()
            }, DELAY_LOCATION)
        }
    }

    private fun getLastLocation() {
        if (FunctionLibrary.checkConnection(requireContext())) {
            if (FunctionLibrary.timeAttendance() || FunctionLibrary.timeAttendanceLate()) {
                if (checkPermission()) {
                    if (isLocationEnabled()) {
                        val locationCallBack = object : LocationCallback() {
                            override fun onLocationResult(locationResult: LocationResult) {
                                super.onLocationResult(locationResult)
                                val location = locationResult.lastLocation
                                val currentLat = location!!.latitude
                                val currentLong = location.longitude
                                val destinationLat = getAddress()[0].latitude
                                val destinationLong = getAddress()[0].longitude
                                lifecycleScope.launch(Dispatchers.Default) {
                                    val distance = FunctionLibrary.calculateDistance(
                                        currentLat, currentLong, destinationLat, destinationLong
                                    ) * 1000
                                    Log.d(TAG, "$TAG_RESULT - $distance")
                                    withContext(Dispatchers.Main) {
                                        if (distance < MEASURING_DISTANCE) {
                                            showDialog()
                                            FunctionLibrary.toast(
                                                context as Activity,
                                                TOAST_SUCCESS,
                                                LOCATION_FOUND,
                                                MotionToastStyle.SUCCESS,
                                                MotionToast.GRAVITY_BOTTOM,
                                                MotionToast.LONG_DURATION,
                                                ResourcesCompat.getFont(
                                                    context as Activity,
                                                    R.font.helveticabold
                                                )
                                            )
                                        } else {
                                            simpleDialog(
                                                OUT_OF_RANGE,
                                                OUT_OF_RANGE_MESSAGE
                                            )
                                            binding.tvCheckIn.visibility = View.VISIBLE
                                        }
                                    }
                                }
                                fusedLocationProviderClient?.removeLocationUpdates(this)
                                stopScanLocation()
                            }
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            fusedLocationProviderClient?.requestLocationUpdates(
                                locationRequest,
                                locationCallBack,
                                Looper.getMainLooper()
                            )
                        }
                    } else {
                        simpleDialog(
                            GPS_STATUS,
                            GPS_MESSAGE
                        )
                        stopScanLocation()
                    }
                } else {
                    stopScanLocation()
                    requestPermission()
                }
            } else {
                stopScanLocation()
                simpleDialog(
                    ATTENDANCE_DENIED,
                    ATTENDANCE_TIME
                )
            }
        } else {
            FunctionLibrary.toast(
                context as Activity,
                TOAST_ERROR,
                PERMISSION_INTERNET,
                MotionToastStyle.ERROR,
                MotionToast.GRAVITY_BOTTOM,
                MotionToast.LONG_DURATION,
                ResourcesCompat.getFont(context as Activity, R.font.helveticabold)
            )
            stopScanLocation()
        }
    }

    private fun simpleDialog(title: String, message: String) {
        context?.let {
            MaterialAlertDialogBuilder(it)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("oke") { _, _ -> }
                .show()
        }
    }

    private fun showDialog() {
        context?.let {
            MaterialAlertDialogBuilder(it)
                .setTitle(resources.getString(R.string.attendancDialog))
                .setMessage(resources.getString(R.string.attendancMessage))
                .setIcon(ResourcesCompat.getDrawable(resources, R.drawable.ic_finger_outline, null))
                .setPositiveButton(resources.getString(R.string.attendancNow)) { _, _ ->
                    val user = auth.currentUser
                    val name = user?.displayName
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (name != null) {
                            inputToFirebase(name)
                        } else {
                            withContext(Dispatchers.Main) {
                                FunctionLibrary.toast(
                                    context as Activity,
                                    TOAST_ERROR,
                                    INPUT_YOUR_NAME,
                                    MotionToastStyle.ERROR,
                                    MotionToast.GRAVITY_BOTTOM,
                                    MotionToast.LONG_DURATION,
                                    ResourcesCompat.getFont(context as Activity,
                                        R.font.helveticabold)
                                )
                            }

                        }
                    }
                }
                .setNegativeButton(resources.getString(R.string.signout_negative)) { _, _ -> }
                .show()
        }
    }

    private fun inputToFirebase(name: String) {
        val database = FirebaseDatabase.getInstance()
        val attendanceRef = database.getReference(REALTIME_DB)
        val userId = attendanceRef.push().key
        val user = User(userId, name, FunctionLibrary.getCurrentTime())
        attendanceRef.child(name).setValue(user)
            .addOnCompleteListener {
                FunctionLibrary.toast(
                    context as Activity,
                    TOAST_SUCCESS,
                    ATTENDANCE_SUCCESSFUL,
                    MotionToastStyle.SUCCESS,
                    MotionToast.GRAVITY_BOTTOM,
                    MotionToast.LONG_DURATION,
                    ResourcesCompat.getFont(context as Activity, R.font.helveticabold)
                )
            }
            .addOnFailureListener {
                FunctionLibrary.toast(
                    context as Activity,
                    TOAST_WARNING,
                    "${it.message}",
                    MotionToastStyle.SUCCESS,
                    MotionToast.GRAVITY_BOTTOM,
                    MotionToast.LONG_DURATION,
                    ResourcesCompat.getFont(context as Activity, R.font.helveticabold)
                )
            }
    }

    private fun getAddress(): List<Address> {
        val destinationPlace = ADDRESS_GEOCODER
        val geocode = Geocoder(context, Locale.getDefault())
        return geocode.getFromLocationName(destinationPlace, MAX_RESULT)
    }

    private fun loadScanLocation() {
        binding.rippleBackground.startRippleAnimation()
        binding.tvScanning.visibility = View.VISIBLE
        binding.tvCheckIn.visibility = View.GONE
        binding.tvCheckInSuccess.visibility = View.GONE
        binding.fabCheckIn.visibility = View.GONE
        binding.gpsAnimation.visibility = View.VISIBLE
    }

    private fun stopScanLocation() {
        binding.rippleBackground.stopRippleAnimation()
        binding.tvScanning.visibility = View.GONE
        binding.tvCheckIn.visibility = View.VISIBLE
        binding.gpsAnimation.visibility = View.GONE
        binding.fabCheckIn.visibility = View.VISIBLE
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager: LocationManager =
            activity?.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER
            )
        ) {
            return true
        }
        return false
    }

    private fun initLocation() {
        fusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(requireContext())
        locationRequest = LocationRequest.create().apply {
            interval = 1000 * 2
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}