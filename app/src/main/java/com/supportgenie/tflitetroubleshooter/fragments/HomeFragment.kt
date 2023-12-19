package com.supportgenie.tflitetroubleshooter.fragments

import android.os.Bundle;
import android.util.Log
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.navigation.fragment.findNavController;
import com.supportgenie.tflitetroubleshooter.R
import com.supportgenie.tflitetroubleshooter.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

        private val TAG = "ObjectDetection"

        private var _fragmentHomeBinding: FragmentHomeBinding? = null
        

        override fun onCreateView(
                inflater: LayoutInflater, container: ViewGroup?,
                savedInstanceState: Bundle?
        ): View? {
                _fragmentHomeBinding = FragmentHomeBinding.inflate(inflater, container, false)
                return _fragmentHomeBinding!!.root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
                super.onViewCreated(view, savedInstanceState)
                val bundle = Bundle()
                _fragmentHomeBinding!!.setUpGuideButton.setOnClickListener {
                        Log.d(TAG, "HomeFragment: buttonToCamera clicked")
                        bundle.putInt("user_choice_key", 1)
                        findNavController().navigate(R.id.action_home_to_camera,bundle)
                }

                _fragmentHomeBinding!!.troubleshooterButton.setOnClickListener {
                        Log.d(TAG, "HomeFragment: troubleshooterButton clicked")
                        bundle.putInt("user_choice_key", 2)
                        findNavController().navigate(R.id.action_home_to_camera,bundle)
                }
        }
}