package com.app.application.controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
@CrossOrigin
public class FileUploadController {

  private static final int CHUNK_SIZE = 1024 * 1024; // 1MB chunks (adjust as needed)
  private final String uploadDir = "C:\\Newfolder\\";

  static Map<String,FileMetadata> cache= new HashMap<String,FileMetadata>();
  
  static Map<String,Map<Integer, Boolean>> cacheChunks= new HashMap<String,Map<Integer, Boolean>>();
  
  @PostMapping("/metadata")
  public ResponseEntity<String> storeMetadata(@RequestBody FileMetadata metadata) {
    String uniqueId = UUID.randomUUID().toString();
    metadata.setId(uniqueId);
    cache.put(uniqueId, metadata);
    // Persist metadata to database or storage (implementation specific)
    System.out.println("Storing metadata for file: " + metadata.getFilename() + " with ID: " + uniqueId);
    // Replace with your logic to store metadata in a database or storage

    return ResponseEntity.ok(uniqueId);
  }

  @PostMapping
  public ResponseEntity<String> uploadChunk(@RequestParam("file") MultipartFile chunkFile,
                                            @RequestParam("chunkNumber") int chunkNumber,
                                            @RequestParam("totalChunks") int totalChunks,
                                            @RequestParam("metadataId") String metadataId,
                                            @RequestParam("checksum") String expectedChecksum) throws IOException {

    String fileName = chunkFile.getOriginalFilename();
    String filePath = uploadDir + cache.get(metadataId).getFilename() + ".part-" + chunkNumber;

    try (FileOutputStream outputStream = new FileOutputStream(filePath, true)) {
      outputStream.write(chunkFile.getBytes());
    } catch (IOException e) {
      // Handle file writing exceptions with more specific error messages
      if (e instanceof FileNotFoundException) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error creating temporary file: " + e.getMessage());
      } else {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error writing chunk: " + e.getMessage());
      }
    }

    // Validate checksum (optional)
    if (chunkNumber == 1) {
      // Retrieve metadata for the first chunk
      // (replace with your logic to retrieve metadata based on metadataId)
      FileMetadata metadata =   cache.get(metadataId);
      if (metadata != null && !expectedChecksum.equals(metadata.getChecksum())) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid checksum for uploaded file.");
      }
    }
    Map<Integer, Boolean> receivedChunks=null;
	synchronized (metadataId) {
	    // Track received chunks (e.g., using a ConcurrentHashMap)
	    receivedChunks =cacheChunks.get(metadataId);
	    if(receivedChunks==null) {
	    	receivedChunks= new ConcurrentHashMap<Integer, Boolean>();
	    	cacheChunks.put(metadataId,receivedChunks);
	    }
	    receivedChunks.put(chunkNumber, true);
	}

    
    
    if (receivedChunks.size() == totalChunks) {
      // All chunks received, reassemble the file
      try {
        reassembleFile(cache.get(metadataId).getFilename(), metadataId, receivedChunks);
      } catch (IOException e) {
        // Handle reassembly exceptions with more specific error messages
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error reassembling file: " + e.getMessage());
      }
      // Clean up temporary files (optional)
      
      
      
    }

    return ResponseEntity.ok("Chunk " + chunkNumber + " uploaded successfully!");
  }

  private void reassembleFile(String fileName, String metadataId, Map<Integer, Boolean> receivedChunks) throws IOException {
    File finalFile = new File(uploadDir + fileName);

    try (FileOutputStream outputStream = new FileOutputStream(finalFile)) {
      for (int i = 1; i <= receivedChunks.size(); i++) {
        File chunkFile = new File(uploadDir + fileName + ".part-" + i);
        if (chunkFile.exists()) {
          if (!receivedChunks.containsKey(i) || !receivedChunks.get(i)) {
            // Log error and consider retry mechanism (optional)
            System.err.println("Chunk " + i + " missing for file " + fileName);
          } else {
            try (FileInputStream inputStream = new FileInputStream(chunkFile)) {
              byte[] buffer = new byte[CHUNK_SIZE];
              int bytesRead;
              while ((bytesRead = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, bytesRead);
              }
            }
          }
        } else {
          // Handle missing chunk (log error, retry mechanism)
          System.err.println("Chunk " + i + " missing for file " + fileName);
          // Implement logic to handle missing chunks (e.g., retry download, notify user)
        }
      }
    }
    finally {
        // Clean up temporary files
        for (int i = 1; i <= receivedChunks.size(); i++) {
          File chunkFile = new File(uploadDir + fileName + ".part-" + i);
          if (chunkFile.exists()) {
            chunkFile.delete();
          }
        }
      }  
  }
}
