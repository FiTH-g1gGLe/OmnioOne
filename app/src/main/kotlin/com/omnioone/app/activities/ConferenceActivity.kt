package com.omnioone.app.activities

import android.os.Bundle
import com.omnioone.commons.extensions.viewBinding
import com.omnioone.commons.helpers.NavigationIcon
import com.omnioone.app.adapters.ConferenceCallsAdapter
import com.omnioone.app.databinding.ActivityConferenceBinding
import com.omnioone.app.helpers.CallManager

class ConferenceActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityConferenceBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(conferenceList))
            setupMaterialScrollListener(binding.conferenceList, binding.conferenceAppbar)
            conferenceList.adapter = ConferenceCallsAdapter(this@ConferenceActivity, conferenceList, ArrayList(CallManager.getConferenceCalls())) {}
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.conferenceAppbar, NavigationIcon.Arrow)
    }
}
