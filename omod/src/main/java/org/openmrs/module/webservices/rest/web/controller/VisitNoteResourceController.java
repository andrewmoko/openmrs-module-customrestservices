package org.openmrs.module.webservices.rest.web.controller;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.api.ObsService;
import org.openmrs.api.context.Context;
import org.openmrs.messagesource.impl.MutableResourceBundleMessageSource;
import org.openmrs.module.appframework.feature.FeatureToggleProperties;
import org.openmrs.module.customrestservices.web.ModuleRestConstants;
import org.openmrs.module.emrapi.adt.AdtService;
import org.openmrs.module.htmlformentry.HtmlForm;
import org.openmrs.module.htmlformentry.HtmlFormEntryService;
import org.openmrs.module.htmlformentryui.fragment.controller.htmlform.EnterHtmlFormFragmentController;
import org.openmrs.ui.framework.SimpleObject;
import org.openmrs.ui.framework.formatter.FormatterService;
import org.openmrs.ui.framework.fragment.FragmentActionUiUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Save & Update Visit Note.
 */
@Controller
@RequestMapping("/rest/" + ModuleRestConstants.VISIT_NOTE_RESOURCE)
public class VisitNoteResourceController {

	private final SimpleDateFormat patientSummaryDateFormat =
	        new SimpleDateFormat("dd/MM/yyyy hh:mm");
	private final Log LOG = LogFactory.getLog(this.getClass());
	private static final String NO_NEWLINE_AT_END_OF_FILE = "\\ No newline at end of file";

	@Autowired
	private FeatureToggleProperties featureToggles;

	@Autowired
	private FormatterService formatterService;

	@Autowired
	private ObsService obsService;

	@ResponseBody
	@RequestMapping(method = RequestMethod.POST)
	public SimpleObject save(
	        @RequestParam("personId") Patient patient,
	        @RequestParam("htmlFormId") Integer htmlFormId,
	        @RequestParam("obs") String obsUuid,
	        @RequestParam(value = "encounterId", required = false) Encounter encounter,
	        @RequestParam(value = "visitId", required = false) Visit visit,
	        @RequestParam(value = "createVisit", required = false) Boolean createVisit,
	        @RequestParam(value = "returnUrl", required = false) String returnUrl,
	        HttpServletRequest request) {

		boolean mergePatientSummaryInfo = false;
		Obs updatedObs = null, existingObs = null;
		if (encounter != null) {
			// check if observation exists
			updatedObs = obsService.getObsByUuid(obsUuid);
			if (updatedObs != null) {
				// retrieve existing obs if it exists
				existingObs = retrieveExistingObs(updatedObs, encounter);
				if (existingObs != null) {
					// check if obs are identical
					String existingObsUuid = existingObs.getUuid();
					if (!obsUuid.equalsIgnoreCase(existingObsUuid)) {
						mergePatientSummaryInfo = true;
					}
				}
			}
		}

		if (mergePatientSummaryInfo) {
			return mergePatientSummaryInfo(updatedObs, existingObs, request);
		} else {
			return saveVisitNote(patient, encounter, visit, createVisit, returnUrl, request);
		}
	}

	private SimpleObject saveVisitNote(Patient patient, Encounter encounter,
	        Visit visit, Boolean createVisit,
	        String returnUrl, HttpServletRequest request) {

		HtmlForm hf = null;

		HtmlFormEntryService service = Context.getService(HtmlFormEntryService.class);
		for (HtmlForm htmlForm : service.getAllHtmlForms()) {
			if (htmlForm.getName().equalsIgnoreCase("visit note 2")) {
				hf = htmlForm;
			}
		}

		if (encounter != null) {
			encounter.setVoided(true);
		}

		AdtService adtService = Context.getService(AdtService.class);
		FragmentActionUiUtils uiUtils = new FragmentActionUiUtils(
		        new MutableResourceBundleMessageSource(), null, null, formatterService);
		try {
			new EnterHtmlFormFragmentController().submit(
			    null, patient, hf, encounter, visit, createVisit, returnUrl,
			    adtService, featureToggles, uiUtils, request);
		} catch (Exception ex) {
			LOG.warn(ex.getMessage());
		}

		Visit updatedVisit = Context.getVisitService().getVisitByUuid(visit.getUuid());
		Obs createdObs = null;
		for (Encounter updatedEncounter : updatedVisit.getEncounters()) {
			String encounterTypeName = updatedEncounter.getEncounterType().getName();
			if (encounterTypeName.equalsIgnoreCase("Visit Note")) {
				for (Obs obs : encounter.getAllObs()) {
					if (obs.getConcept().getName().getName().equalsIgnoreCase("text of encounter note")) {
						createdObs = obs;
						break;
					}
				}
			}
		}

		return SimpleObject.create(
		    "success", true,
		    "encounterId", createdObs.getEncounter().getUuid(),
		    "w12", createdObs.getValueText(),
		    "observationUuid", createdObs.getUuid());
	}

	/**
	 * This method compares between the current and previous obs patient summary (clinical note) merges text
	 * @param updatedObs
	 * @param existingObs
	 * @param request
	 * @return
	 */
	private SimpleObject mergePatientSummaryInfo(Obs updatedObs, Obs existingObs, HttpServletRequest request) {
		String existingPatientSummary = existingObs.getValueText();
		if (existingPatientSummary.equalsIgnoreCase("")) {
			// no need for merging
			existingObs.setVoided(true);
		} else {
			updatedObs.setValueText(
			        mergeTextAndShowConflicts(existingObs, updatedObs, request));
		}

		obsService.voidObs(existingObs, "void patient summary obs");
		Obs mergedObs = Obs.newInstance(updatedObs);
		mergedObs.setVoided(false);
		Obs createdObs = obsService.saveObs(mergedObs, "create merged patient summary obs");

		return SimpleObject.create(
		    "success", true,
		    "encounterId", createdObs.getEncounter().getUuid(),
		    "w12", createdObs.getValueText(),
		    "observationUuid", createdObs.getUuid());
	}

	private Obs retrieveExistingObs(Obs updatedObs, Encounter encounter) {
		for (Obs obs : encounter.getAllObs()) {
			if (obs.getConcept().getUuid().equalsIgnoreCase(updatedObs.getConcept().getUuid())) {
				return obs;
			}
		}

		return null;
	}

	private String mergeTextAndShowConflicts(Obs existingObs, Obs updatedObs, HttpServletRequest request) {
		StringBuilder mergedText = new StringBuilder();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			RawText rawText1 = new RawText(existingObs.getValueText().getBytes());
			RawText rawText2 = new RawText(request.getParameter("w12").getBytes());
			EditList diffList = new EditList();
			diffList.addAll(new HistogramDiff().diff(RawTextComparator.DEFAULT, rawText1, rawText2));
			new DiffFormatter(out).format(diffList, rawText1, rawText2);
		} catch (IOException ex) {
			out = null;
		}

		String createdBy = existingObs.getCreator().getGivenName();
		String createdOn = patientSummaryDateFormat.format(existingObs.getDateCreated());
		String updatedBy = updatedObs.getCreator().getGivenName();
		String updatedOn = patientSummaryDateFormat.format(new Date());

		String[] mergedTxts = StringUtils.split(out.toString(), "\n");
		if (mergedTxts.length > 1) {
			mergedTxts[0] = "";
			mergedText.append(insertMetadata(createdBy, createdOn));
			boolean firstOccurenceNewLine = false;
			for (String text : mergedTxts) {
				// replace first occurrence of NO_NEWLINE_AT_END_OF_FILE with updated Metadata.
				if (!firstOccurenceNewLine && text.contains(NO_NEWLINE_AT_END_OF_FILE)) {
					text = text.
					        replaceFirst(NO_NEWLINE_AT_END_OF_FILE, insertMetadata(updatedBy, updatedOn));
					firstOccurenceNewLine = true;
				}

				mergedText.append(text
				        .replaceAll(NO_NEWLINE_AT_END_OF_FILE, "")
				        .replaceAll("\\\\", "")
				        .replaceAll("\\+", "")
				        .replaceAll("-", ""));
			}
		}

		return mergedText.toString();
	}

	private String insertMetadata(String author, String changedOn) {
		StringBuilder metadata = new StringBuilder();
		metadata.append("\n[Author=");
		metadata.append(author);
		metadata.append(" Created=");
		metadata.append(changedOn);
		metadata.append("]\n");
		return metadata.toString();
	}
}
