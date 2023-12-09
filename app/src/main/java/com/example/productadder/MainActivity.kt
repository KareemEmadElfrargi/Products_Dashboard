package com.example.productadder

import android.content.Intent
import android.content.Intent.ACTION_GET_CONTENT
import android.graphics.Bitmap
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.productadder.databinding.ActivityMainBinding
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val selectedImages = mutableListOf<Uri>()
    private val selectedColors = mutableListOf<Int>()
    private val productStorage = Firebase.storage.reference
    private val firestore = Firebase.firestore



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.buttonColorPicker.setOnClickListener {
            ColorPickerDialog.Builder(this)
                .setTitle("Product Color")
                .setPositiveButton("Select", object :ColorEnvelopeListener{
                    override fun onColorSelected(envelope: ColorEnvelope?, fromUser: Boolean) {
                        envelope?.let{
                            selectedColors.add(it.color)
                            updateColorsView()
                        }
                    }

                })
                .setNegativeButton("Cancel",){ colorPickerDialog,_ ->
                    colorPickerDialog.dismiss()
                }.show()
        }

        val selectImagesActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result ->
            if (result.resultCode == RESULT_OK){
                val intent = result.data

                //Multi images selected
                if (intent?.clipData!=null){
                    val count = intent.clipData?.itemCount ?: 0
                    (0 until count).forEach {
                        val imageUri = intent.clipData?.getItemAt(it)?.uri
                        imageUri?.let {
                            selectedImages.add(it)
                        }
                    }
                }else{
                    val imageUri = intent?.data
                    imageUri?.let {
                        selectedImages.add(it)
                    }
                }
                updateImagesView()
            }
        }
        binding.buttonImagesPicker.setOnClickListener {
            // access from gallery photos
            val intent = Intent(ACTION_GET_CONTENT)
            // select a lot of image
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
            intent.type = "image/*" // * any type of image like png..
            selectImagesActivityResult.launch(intent)
        }


    }

    private fun updateImagesView() {
        binding.tvSelectedImages.text = selectedImages.size.toString()
    }

    // modify color to hex-Decimal
    private fun updateColorsView() {
        var colors = ""
        selectedColors.forEach { color ->
            // old color and new colors added
            colors = "$colors ${Integer.toHexString(color)}"
        }
        binding.tvSelectedColors.text = colors
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.saveProduct){
            val productValidation = validateInformation()
            if (!productValidation){
                Toast.makeText(this@MainActivity,"Check You inputs",Toast.LENGTH_SHORT).show()
                return false
            }

            saveProduct()

        }
        return super.onOptionsItemSelected(item)
    }

    private fun saveProduct() {
        val name = binding.edName.text.toString().trim()
        val category = binding.edCategory.text.toString().trim()
        val price = binding.edPrice.text.toString().trim()
        val offerPercentage = binding.offerPercentage.text.toString().trim()
        val description = binding.edDescription.text.toString().trim()
        val sizes = getSizesList(binding.edSizes.text.toString())
        val imagesByteArrays = getImagesByteArray()
        val images = mutableListOf<String>()


        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main){
                showLoadingProgressbar()
            }

            try {

                async {
                    imagesByteArrays.forEach {
                        val idImage = UUID.randomUUID().toString()
                            launch {
                                val imageStorage = productStorage.child("Products/images/$idImage")
                                val result = imageStorage.putBytes(it).await()
                                val downloadUrl = result.storage.downloadUrl.await().toString() // get the ref image
                                images.add(downloadUrl)
                            }
                    }

                }.await()
            }catch (e:Exception){
                e.printStackTrace()
                withContext(Dispatchers.Main){
                    hideLoadingProgressbar()
                }
            }

            val product = Product(
                UUID.randomUUID().toString(),
                name,
                category,
                price.toFloat(),
                if (offerPercentage.isEmpty()) null else offerPercentage.toFloat(),
                description.ifEmpty { null },
                if (selectedColors.isEmpty()) null else selectedColors,
                sizes,
                images
            )

            firestore.collection("Products").add(product)
                .addOnSuccessListener {
                    hideLoadingProgressbar()

                }.addOnFailureListener{
                    hideLoadingProgressbar()
                    Log.e("Error",it.message.toString())
                }


        }

    }

    private fun hideLoadingProgressbar() {
        binding.progressBar.visibility = View.INVISIBLE
    }
    private fun showLoadingProgressbar() {
        binding.progressBar.visibility = View.VISIBLE
    }
    // Check Chat-GPT | explain this please
    private fun getImagesByteArray(): List<ByteArray> {
        val imagesByteArray = mutableListOf<ByteArray>()
        selectedImages.forEach { imageUri ->
            val stream = ByteArrayOutputStream() // to store the converted image.
            val imageBitMap = MediaStore.Images.Media.getBitmap(contentResolver,imageUri) // This assumes that imageUri is a valid URI pointing // to an image
            //Compresses the Bitmap to a JPEG format with a quality of 100 and writes the compressed data to the "ByteArrayOutputStream"
            if (imageBitMap.compress(Bitmap.CompressFormat.JPEG,100,stream)){
               // Converts the content of the ByteArrayOutputStream to a ByteArray
                imagesByteArray.add(stream.toByteArray())
            }
        }
        return imagesByteArray
    }
    // S,M,L,XL
    private fun getSizesList(sizesString: String): List<String>? {
        if (sizesString.isEmpty())
            return null
        val sizesList = sizesString.split(",")
        return sizesList
    }
    private fun validateInformation(): Boolean {
        if (binding.edPrice.text.toString().trim().isEmpty()){
            return false
        }
        if (binding.edName.text.toString().trim().isEmpty()){
            return false
        }
        if (binding.edCategory.text.toString().trim().isEmpty()){
            return false
        }
        if (selectedImages.isEmpty()){
            return false
        }


        return true
    }
}