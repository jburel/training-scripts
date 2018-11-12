import java.util.ArrayList
import java.lang.StringBuffer
import java.nio.ByteBuffer
import java.io.File
import java.io.FileInputStream
import java.io.PrintWriter

import java.nio.file.Files

// OMERO Dependencies
import omero.gateway.Gateway
import omero.gateway.LoginCredentials
import omero.gateway.SecurityContext
import omero.gateway.facility.BrowseFacility
import omero.gateway.facility.DataManagerFacility
import omero.gateway.facility.ROIFacility
import omero.gateway.facility.TablesFacility
import omero.log.SimpleLogger
import omero.model.ChecksumAlgorithmI
import omero.model.FileAnnotationI
import omero.model.OriginalFileI
import omero.model.enums.ChecksumAlgorithmSHA1160

import static omero.rtypes.rlong
import static omero.rtypes.rstring

import omero.gateway.model.DatasetData
import omero.gateway.model.FileAnnotationData
import omero.gateway.model.ImageData
import omero.gateway.model.TableData
import omero.gateway.model.TableDataColumn
import omero.model.DatasetI
import omero.model.ImageI

import org.openmicroscopy.shoola.util.roi.io.ROIReader

import loci.formats.FormatTools
import loci.formats.ImageTools
import loci.common.DataTools

import ij.IJ
import ij.ImagePlus
import ij.ImageStack
import ij.process.ByteProcessor
import ij.process.ShortProcessor
import ij.plugin.frame.RoiManager
import ij.measure.ResultsTable


// Setup
// =====

// OMERO Server details
HOST = "nightshade.openmicroscopy.org"
PORT = 4064

//  parameters to edit
dataset_id = 75287
USERNAME = "username"
PASSWORD = "password"
path_to_macro ="path/Tryp_foci_analysis.ijm"

def connect_to_omero() {
    "Connect to OMERO"

    credentials = new LoginCredentials()
    credentials.getServer().setHostname(HOST)
    credentials.getServer().setPort(PORT)
    credentials.getUser().setUsername(USERNAME.trim())
    credentials.getUser().setPassword(PASSWORD.trim())
    simpleLogger = new SimpleLogger()
    gateway = new Gateway(simpleLogger)
    gateway.connect(credentials)
    return gateway
}

def get_images(gateway, ctx, dataset_id) {
    "List all image's ids contained in a Dataset"

    browse = gateway.getFacility(BrowseFacility)

    ids = new ArrayList(1)
    ids.add(new Long(dataset_id))
    return browse.getImagesForDatasets(ctx, ids)
}


def open_image_plus(HOST, USERNAME, PASSWORD, PORT, group_id, image_id) {
    "Open the image using the Bio-Formats Importer"

    StringBuffer options = new StringBuffer()
    options.append("location=[OMERO] open=[omero:server=")
    options.append(HOST)
    options.append("\nuser=")
    options.append(USERNAME.trim())
    options.append("\nport=")
    options.append(PORT)
    options.append("\npass=")
    options.append(PASSWORD.trim())
    options.append("\ngroupID=")
    options.append(group_id)
    options.append("\niid=")
    options.append(image_id)
    options.append("] ")
    options.append("windowless=true view=Hyperstack ")
    IJ.runPlugIn("loci.plugins.LociImporter", options.toString())
}


def save_rois_to_omero(ctx, image_id, imp) {
    " Save ROI's back to OMERO"
    reader = new ROIReader()
    roi_list = reader.readImageJROIFromSources(image_id, imp)
    roi_facility = gateway.getFacility(ROIFacility)
    result = roi_facility.saveROIs(ctx, image_id, exp_id, roi_list)

    roivec = new ArrayList()
    j = result.iterator()
    while (j.hasNext()) {
        roidata = j.next()
        roi_id = roidata.getId()

        i = roidata.getIterator()
        while (i.hasNext()) {
            roi = i.next()
            shape = roi[0]
            t = shape.getZ()
            z = shape.getT()
            c = shape.getC()
            shape_id = shape.getId()
            roivec.add([roi_id, shape_id, z, c, t])
        }
    }
    return roivec
}

def upload_csv_to_omero(ctx, file, id) {
    "Upload the CSV file and attach it to the specified image"
    svc = gateway.getFacility(DataManagerFacility)
    file_size = file.length()
    original_file = new OriginalFileI()
    original_file.setName(rstring(file.getName()))
    original_file.setPath(rstring(file.getAbsolutePath()))
    original_file.setSize(rlong(file_size))
    checksum_algorithm = new ChecksumAlgorithmI()
    checksum_algorithm.setValue(rstring(ChecksumAlgorithmSHA1160.value))
    original_file.setHasher(checksum_algorithm)
    original_file.setMimetype(rstring("text/csv"))
    original_file = svc.saveAndReturnObject(ctx, original_file)
    store = gateway.getRawFileService(ctx)

    // Open file and read stream
    INC = 262144
    pos = 0
    buf = new byte[INC]
    ByteBuffer bbuf = null
    stream = null
    try {
        store.setFileId(original_file.getId().getValue())
        stream = new FileInputStream(file)
        while ((rlen = stream.read(buf)) > 0) {
            store.write(buf, pos, rlen)
            pos += rlen
            bbuf = ByteBuffer.wrap(buf)
            bbuf.limit(rlen)
        }
        original_file = store.save()
    } finally {
        if (stream != null) {
            stream.close()
        }
        store.close()
    }
    // create the file annotation
    //namespace = "training.demo"
    fa = new FileAnnotationI()
    fa.setFile(original_file)
    //fa.setNs(rstring(namespace))

    data_object = new ImageData(new ImageI(id, false)) 
    svc.attachAnnotation(ctx, new FileAnnotationData(fa), data_object)
}

def save_measurements(name, id)
{
    //Create the result file
    tmp_dir = Files.createTempDirectory("Fiji_csv")
    path = tmp_dir.resolve(name)
    file_path = Files.createFile(path)
    file = new File(file_path.toString())

    // create a CSV file and upload it
    rt = ResultsTable.getResultsTable()
    rt.save(file_path.toString())

    upload_csv_to_omero(ctx, file, id)

    //delete the local copy of the temporary file and directory
    dir = new File(tmp_dir.toString())
    entries = dir.listFiles()
    for (i = 0; i < entries.length; i++) {
        entries[i].delete()
    }
    dir.delete()
}

// Prototype analysis example
gateway = connect_to_omero()
exp = gateway.getLoggedInUser()
group_id = exp.getGroupId()
ctx = new SecurityContext(group_id)
exp_id = exp.getId()

// get all images_ids in an omero dataset
images = get_images(gateway, ctx, dataset_id)


count = 0
//Close all windows before starting
IJ.run("Close All")

images.each() { image ->
    // Open the image
    id = image.getId()
    open_image_plus(HOST, USERNAME, PASSWORD, PORT, group_id, String.valueOf(id))
    imp = IJ.getImage()
    // Analyse the images. This section could be replaced by any other macro
    IJ.runMacroFile(path_to_macro)
    // Save the ROIs back to OMERO
    roivec = save_rois_to_omero(ctx, id, imp)
    println "creating summary results for image ID " + id
    // Close the various components
    //Create the result file
    name = image.getName()+".csv"
    save_measurements(name, id)
    IJ.selectWindow("Results")
    IJ.run("Close")
    IJ.selectWindow("ROI Manager")
    IJ.run("Close")
    imp.changes = false     // Prevent "Save Changes?" dialog
    imp.close()
}


// Close the connection
gateway.disconnect()
println "processing done"