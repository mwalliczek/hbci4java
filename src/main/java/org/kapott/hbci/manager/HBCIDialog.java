/**********************************************************************
 *
 * This file is part of HBCI4Java.
 * Copyright (c) 2001-2008 Stefan Palme
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *
 **********************************************************************/

package org.kapott.hbci.manager;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import org.kapott.hbci.GV.HBCIJobImpl;
import org.kapott.hbci.callback.HBCICallback;
import org.kapott.hbci.dialog.DialogContext;
import org.kapott.hbci.dialog.DialogEvent;
import org.kapott.hbci.dialog.HBCIDialogInit;
import org.kapott.hbci.dialog.HBCIMessage;
import org.kapott.hbci.dialog.HBCIMessageQueue;
import org.kapott.hbci.exceptions.HBCI_Exception;
import org.kapott.hbci.passport.HBCIPassportInternal;
import org.kapott.hbci.passport.HBCIPassportList;
import org.kapott.hbci.status.HBCIDialogStatus;
import org.kapott.hbci.status.HBCIInstMessage;
import org.kapott.hbci.status.HBCIMsgStatus;

/* @brief A class for managing exactly one HBCI-Dialog

    A HBCI-Dialog consists of a number of HBCI-messages. These
    messages will be sent (and the responses received) one
    after the other, without timegaps between them (to avoid
    network timeout problems).

    The messages generated by a HBCI-Dialog are at first DialogInit-Message,
    after that a message that contains one ore more "Geschaeftsvorfaelle"
    (i.e. the stuff that you really want to do via HBCI), and at last
    a DialogEnd-Message.

    In this class we have two API-levels, a mid-level API (for manually
    creating and processing dialogs) and a high-level API (for automatic
    creation of typical HBCI-dialogs). For each method the API-level is
    given in its description 
*/
public final class HBCIDialog
{
    private boolean     isAnon;
    private String      anonSuffix;
    private String      dialogid;  /* The dialogID for this dialog (unique for each dialog) */
    private long        msgnum;    /* An automatically managed message counter. */
    private HBCIMessageQueue queue;
    private Properties listOfGVs = new Properties();
    private HBCIHandler parentHandler;

    public HBCIDialog(HBCIHandler parentHandler)
    {
        HBCIUtils.log("creating new dialog",HBCIUtils.LOG_DEBUG);

        this.parentHandler=parentHandler;
        this.isAnon=((HBCIPassportInternal)parentHandler.getPassport()).isAnonymous();
        this.anonSuffix=isAnon?"Anon":"";
        
        this.reset();
    }
    
    public HBCIHandler getParentHandler()
    {
        return this.parentHandler;
    }

    /** @brief Processing the DialogInit stage and updating institute and user data from the server
               (mid-level API).

        This method processes the dialog initialization stage of an HBCIDialog. It creates
        a new rawMsg in the kernel and processes it. The return values will be
        passed to appropriate methods in the @c institute and @c user objects to
        update their internal state with the data received from the institute. */
    private HBCIMsgStatus doDialogInit()
    {
        HBCIMsgStatus ret = null;
        
        try {
            HBCIPassportInternal mainPassport=(HBCIPassportInternal)getParentHandler().getPassport();
            HBCIKernelImpl       kernel=(HBCIKernelImpl)getParentHandler().getKernel();
            
            // autosecmech
            HBCIUtils.log("checking whether passport is supported (but ignoring result)",HBCIUtils.LOG_DEBUG);
            boolean s=mainPassport.isSupported();
            HBCIUtils.log("passport supported: "+s,HBCIUtils.LOG_DEBUG);
            
            HBCIUtils.log(HBCIUtilsInternal.getLocMsg("STATUS_DIALOG_INIT"),HBCIUtils.LOG_INFO);
            HBCIUtilsInternal.getCallback().status(mainPassport,HBCICallback.STATUS_DIALOG_INIT,null);
    
            // Dialog-Context erzeugen
            final DialogContext ctx = DialogContext.create(kernel,mainPassport);
            ctx.setDialog(this);
            ctx.setAnonymous(this.isAnon);
            
            // Dialog-Initialisierung senden
            final HBCIDialogInit init = new HBCIDialogInit();
            ret = init.execute(ctx);
            
            if (ret.isOK())
            {
                final Properties result = ret.getData();
                final HBCIInstitute inst = new HBCIInstitute(kernel,mainPassport,false);
                inst.updateBPD(result);
                inst.extractKeys(result);
    
                final HBCIUser user = new HBCIUser(kernel,mainPassport,false);
                user.updateUPD(result);
               
                mainPassport.saveChanges();
    
                this.msgnum = ctx.getMsgNum();
                this.dialogid = ctx.getDialogId();

                HBCIInstMessage msg=null;
                for (int i=0;true;i++)
                {
                    try
                    {
                        String header=HBCIUtilsInternal.withCounter("KIMsg",i);
                        msg=new HBCIInstMessage(result,header);
                    }
                    catch (Exception e)
                    {
                        break;
                    }
                    HBCIUtilsInternal.getCallback().callback(mainPassport,
                                                     HBCICallback.HAVE_INST_MSG,
                                                     msg.toString(),
                                                     HBCICallback.TYPE_NONE,
                                                     new StringBuffer());
                }
            }

            HBCIUtilsInternal.getCallback().status(mainPassport,HBCICallback.STATUS_DIALOG_INIT_DONE,new Object[] {ret,dialogid});
        }
        catch (Exception e)
        {
            if (ret == null)
                ret = new HBCIMsgStatus();
            ret.addException(e);
        }

        return ret;
    }
    
    /**
     * Fuehrt die eigentlichen Geschaeftsvorfaelle aus.
     * @return
     */
    private HBCIMsgStatus[] doJobs()
    {
        HBCIUtils.log(HBCIUtilsInternal.getLocMsg("LOG_PROCESSING_JOBS"),HBCIUtils.LOG_INFO);
        
        final HBCIHandler h = this.getParentHandler();
        final HBCIKernelImpl k = (HBCIKernelImpl) h.getKernel();
        final HBCIPassportInternal p = (HBCIPassportInternal) h.getPassport();
        
        final DialogContext ctx = DialogContext.create(k,p);
        ctx.setDialog(this);
        ctx.setAnonymous(this.isAnon);

        final ArrayList<HBCIMsgStatus> allStatuses = new ArrayList<HBCIMsgStatus>();

        int msgCount = 0;
        HBCIMessage msg = null;
        
        while (true)
        {
            p.onDialogEvent(DialogEvent.JOBS_CREATED,ctx);
            msg = this.queue.poll();
            if (msg == null)
            {
                HBCIUtils.log("dialog completed after " + msgCount + " messages",HBCIUtils.LOG_DEBUG);
                break;
            }
            
            final List<HBCIJobImpl> tasks = msg.getTasks();
            
            if (tasks.size() == 0)
            {
                HBCIUtils.log("no tasks in message #" + msgCount + ", skipping",HBCIUtils.LOG_WARN);
                continue;
            }

            msgCount++;
            HBCIMsgStatus msgstatus = null;
            
            try
            {
                ////////////////////////////////////////////////////////////////////
                // Basis-Daten der Nachricht
                final HBCIPassportList msgPassports = new HBCIPassportList();
                HBCIUtils.log("generating msg #" + msgCount,HBCIUtils.LOG_DEBUG);
                    
                k.rawNewMsg("CustomMsg");
                k.rawSet("MsgHead.dialogid", dialogid);
                k.rawSet("MsgHead.msgnum", this.getMsgNum());
                k.rawSet("MsgTail.msgnum", this.getMsgNum());
                //
                ////////////////////////////////////////////////////////////////////

                ////////////////////////////////////////////////////////////////////
                // Jobs hinzufuegen
                int taskNum = 0;
                for (HBCIJobImpl task:tasks)
                {
                    final String name = task.getName();

                    if (task.skipped())
                    {
                        HBCIUtils.log("skipping task " + name, HBCIUtils.LOG_DEBUG);
                        continue;
                    }
                    
                    HBCIUtils.log("adding task " + name,HBCIUtils.LOG_DEBUG);
                    HBCIUtilsInternal.getCallback().status(p,HBCICallback.STATUS_SEND_TASK,task);

                    // Uebernimmt den aktuellen loop-Wert in die Lowlevel-Parameter
                    task.applyOffset();
                    task.setIdx(taskNum);
                    
                    // Daten des Tasks in den Kernel uebernehmen
                    {
                        final String header = HBCIUtilsInternal.withCounter("GV",taskNum);
                        final Properties props = task.getLowlevelParams();
                        for (Enumeration e = props.keys(); e.hasMoreElements();)
                        {
                            String key = (String) e.nextElement();
                            k.rawSet(header + "." + key,props.getProperty(key));
                        }
                    }
                    
                    // additional passports für diesen task ermitteln und zu den passports für die aktuelle nachricht
                    // hinzufügen; doppelgänger werden schon von  HBCIPassportList.addPassport() herausgefiltert
                    msgPassports.addAll(task.getSignaturePassports());
                    taskNum++;
                }
                //
                ////////////////////////////////////////////////////////////////////
                
                // Das passiert immer dann, wenn wir in der Message nur ein HKTAN#2 aus Prozess-Variante 2 hatten.
                // Dieses aufgrund einer 3076-SCA-Ausnahme aber nicht benoetigt wird.
                if (taskNum == 0)
                {
                    HBCIUtils.log("no tasks in message #" + msgCount + ", skipping",HBCIUtils.LOG_DEBUG);
                    continue;
                }
                    
                ////////////////////////////////////////////////////////////////////
                // Nachricht an die Bank senden
                msgstatus = k.rawDoIt(msgPassports,HBCIKernelImpl.SIGNIT,HBCIKernelImpl.CRYPTIT,HBCIKernelImpl.NEED_CRYPT);
                this.nextMsgNum();
                //
                ////////////////////////////////////////////////////////////////////

                ////////////////////////////////////////////////////////////////////
                // Antworten auswerten
                final int segnum = this.findTaskSegment(msgstatus);
                if (segnum != 0)
                {           
                    // für jeden Task die entsprechenden Rückgabedaten-Klassen füllen
                    for (HBCIJobImpl task:tasks)
                    {
                        final String name = task.getName();

                        if (task.skipped())
                        {
                            HBCIUtils.log("skipping results for task " + name, HBCIUtils.LOG_DEBUG);
                            continue;
                        }

                        try
                        {
                            HBCIUtils.log("filling results for task " + name, HBCIUtils.LOG_DEBUG);
                            task.fillJobResult(msgstatus,segnum);
                            HBCIUtilsInternal.getCallback().status(p,HBCICallback.STATUS_SEND_TASK_DONE,task);
                        }
                        catch (Exception e)
                        {
                            msgstatus.addException(e);
                        }
                    }
                }
                //
                ////////////////////////////////////////////////////////////////////

                ////////////////////////////////////////////////////////////////////
                // Wenn wir Fehler haben, brechen wir den kompletten Dialog ab
                if (msgstatus.hasExceptions())
                {
                    HBCIUtils.log("aborting current loop because of errors",HBCIUtils.LOG_ERR);
                    break;
                }
                //
                ////////////////////////////////////////////////////////////////////

                ////////////////////////////////////////////////////////////////////
                // Jobs erneut ausfuehren, falls noetig.
                HBCIMessage newMsg = null;
                for (HBCIJobImpl task:tasks)
                {
                    final String name = task.getName();

                    if (task.skipped())
                    {
                        HBCIUtils.log("skipping repeat for task " + name, HBCIUtils.LOG_DEBUG);
                        continue;
                    }
                    
                    HBCIJobImpl redo = task.redo();
                    if (redo != null)
                    {
                        // Nachricht bei Bedarf erstellen und an die Queue haengen
                        if (newMsg == null)
                        {
                            newMsg = new HBCIMessage();
                            queue.append(newMsg);
                        }
                        
                        // Task hinzufuegen
                        HBCIUtils.log("repeat task " + redo.getName(),HBCIUtils.LOG_DEBUG);
                        newMsg.append(redo);
                    }
                }
                //
                ////////////////////////////////////////////////////////////////////
            }
            catch (Exception e)
            {
                HBCIUtils.log(e);
                if (msgstatus != null)
                    msgstatus.addException(e);
            }
            finally
            {
                if (msgstatus != null)
                    allStatuses.add(msgstatus);
            }
        }

        return allStatuses.size() > 0 ? allStatuses.toArray(new HBCIMsgStatus[allStatuses.size()]) : new HBCIMsgStatus[0];
    }
    
    /**
     * Sucht in den Ergebnis-Daten des Kernels nach der ersten Segment-Nummer mit einem Task-Response.
     * @param msgstatus die Ergebnis-Daten des Kernels.
     * @return die Nummer des Segments oder -1, wenn keines gefunden wurde.
     */
    private int findTaskSegment(HBCIMsgStatus msgstatus)
    {
        final Properties result = msgstatus.getData();
        
        // searching for first segment number that belongs to the custom_msg
        // we look for entries like {"1","CustomMsg.GV*"} and so on (this data is inserted from the HBCIKernelImpl.rawDoIt() method),
        // until we find the first segment containing a task
        int segnum = 1;
        while (segnum < 1000) // Wir brauchen ja nicht endlos suchen
        {
            final String path = result.getProperty(Integer.toString(segnum));
            
            // Wir sind am Ende der Segmente angekommen
            if (path == null)
                return -1;

            // Wir haben ein GV-Antwort-Segment gefunden
            if (path.startsWith("CustomMsg.GV"))
              return segnum;

            // naechstes Segment
            segnum++;
        }
        
        return -1;
    }

    /** @brief Processes the DialogEnd stage of an HBCIDialog (mid-level API).
        Works similarily to doDialogInit(). */
    @Deprecated
    private HBCIMsgStatus doDialogEnd()
    {
        
        // Neues Dialog-Ende: Koennen wir noch nicht verwenden, weil wir hier noch nicht die aktuelle Nachrichten-Nummer haben.
//        HBCIDialogEnd end = new HBCIDialogEnd(Flag.ACCEPT_ERROR);
//        end.execute(ctx);
        
        HBCIMsgStatus ret=new HBCIMsgStatus();
        
        HBCIHandler          handler=getParentHandler();
        HBCIPassportInternal mainPassport=(HBCIPassportInternal)handler.getPassport();
        HBCIKernelImpl       kernel=(HBCIKernelImpl)handler.getKernel();
        
        try {
            HBCIUtils.log(HBCIUtilsInternal.getLocMsg("LOG_DIALOG_END"),HBCIUtils.LOG_INFO);
            HBCIUtilsInternal.getCallback().status(mainPassport,HBCICallback.STATUS_DIALOG_END,null);
    
            kernel.rawNewMsg("DialogEnd"+anonSuffix);
            kernel.rawSet("DialogEndS.dialogid", dialogid);
            kernel.rawSet("MsgHead.dialogid", dialogid);
            kernel.rawSet("MsgHead.msgnum", getMsgNum());
            kernel.rawSet("MsgTail.msgnum", getMsgNum());
            nextMsgNum();
            ret=kernel.rawDoIt(!isAnon && HBCIKernelImpl.SIGNIT,
                               !isAnon && HBCIKernelImpl.CRYPTIT,
                               !isAnon && HBCIKernelImpl.NEED_CRYPT);

            HBCIUtilsInternal.getCallback().status(mainPassport,HBCICallback.STATUS_DIALOG_END_DONE,ret);
        } catch (Exception e) {
            ret.addException(e);
        }

        return ret;
    }

    /** führt einen kompletten dialog mit allen zu diesem
        dialog gehoerenden nachrichten/tasks aus.

        bricht diese methode mit einer exception ab, so muessen alle
        nachrichten bzw. tasks, die noch nicht ausgeführt wurden, 
        von der aufrufenden methode neu erzeugt werden */
    public HBCIDialogStatus doIt()
    {
        try {
            HBCIUtils.log("executing dialog",HBCIUtils.LOG_DEBUG);
            final HBCIDialogStatus ret = new HBCIDialogStatus();
            
            HBCIMsgStatus initStatus = this.doDialogInit();
            ret.setInitStatus(initStatus);
                
            if (initStatus.isOK())
            {
                ret.setMsgStatus(doJobs());
                ret.setEndStatus(doDialogEnd());
            }
            
            return ret;
        } finally {
            reset();
        }
    }

    private void reset()
    {
        try
        {
            dialogid=null;
            msgnum=1;
            this.queue = new HBCIMessageQueue();
            listOfGVs.clear();
        }
        catch (Exception e)
        {
            HBCIUtils.log(e);
        }
    }
    
    public String getDialogID()
    {
        return dialogid;
    }

    public String getMsgNum()
    {
        return Long.toString(msgnum);
    }

    public void nextMsgNum()
    {
        msgnum++;
    }
    
    private int getTotalNumberOfGVSegsInCurrentMessage()
    {
        int total=0;
        
        for (Enumeration e=listOfGVs.keys(); e.hasMoreElements(); ) {
            String hbciCode=(String)e.nextElement();
            int    counter=Integer.parseInt(listOfGVs.getProperty(hbciCode));
            total+=counter;
        }
        
        HBCIUtils.log("there are currently "+total+" GV segs in this message", HBCIUtils.LOG_DEBUG);
        return total;
    }

    public void addTask(HBCIJobImpl job)
    {
        // TODO: hier evtl. auch überprüfen, dass nur jobs mit den gleichen
        // signatur-anforderungen (anzahl) in einer msg stehen
        
        try {
            HBCIUtils.log(HBCIUtilsInternal.getLocMsg("EXCMSG_ADDJOB",job.getName()),HBCIUtils.LOG_DEBUG);
            job.verifyConstraints();
            
            // check bpd.numgva here
            String hbciCode=job.getHBCICode();
            
            int    gva_counter=listOfGVs.size();
            String counter_st=listOfGVs.getProperty(hbciCode);
            int    gv_counter=(counter_st!=null)?Integer.parseInt(counter_st):0;
            int    total_counter=getTotalNumberOfGVSegsInCurrentMessage();
            
            gv_counter++;
            total_counter++;
            if (counter_st==null) {
                gva_counter++;
            }

            HBCIPassportInternal passport=(HBCIPassportInternal)getParentHandler().getPassport();
            
            // BPD: max. Anzahl GV-Arten
            int maxGVA=passport.getMaxGVperMsg();
            // BPD: max. Anzahl von Job-Segmenten eines bestimmten Typs
            int maxGVSegJob=job.getMaxNumberPerMsg();        
            // Passport: evtl. weitere Einschränkungen bzgl. der Max.-Anzahl 
            // von Auftragssegmenten pro Nachricht
            int maxGVSegTotal=passport.getMaxGVSegsPerMsg();  
            
            if ((maxGVA>0 && gva_counter>maxGVA) || 
                    (maxGVSegJob>0 && gv_counter>maxGVSegJob) ||
                    (maxGVSegTotal>0 && total_counter>maxGVSegTotal)) 
            {
                if (maxGVSegTotal>0 && total_counter>maxGVSegTotal) {
                    HBCIUtils.log(
                            "have to generate new message because current type of passport only allows "+maxGVSegTotal+" GV segs per message",
                            HBCIUtils.LOG_DEBUG);
                } else {
                    HBCIUtils.log(
                            "have to generate new message because of BPD restrictions for number of tasks per message; adding job to this new message",
                            HBCIUtils.LOG_DEBUG);
                }
                newMsg();
                gv_counter=1;
                total_counter=1;
            }

            listOfGVs.setProperty(hbciCode,Integer.toString(gv_counter));
            this.queue.getLast().append(job);
        } catch (Exception e) {
            String msg=HBCIUtilsInternal.getLocMsg("EXCMSG_CANTADDJOB",job.getName());
            if (!HBCIUtilsInternal.ignoreError(null,"client.errors.ignoreAddJobErrors",
                                       msg+": "+HBCIUtils.exception2String(e))) {
                throw new HBCI_Exception(msg,e);
            }
            
            HBCIUtils.log("task "+job.getName()+" will not be executed in current dialog",HBCIUtils.LOG_ERR);
        }
    }
    
    /**
     * Liefert die Nachrichten-Queue des Dialogs.
     * @return die Nachrichten-Queue des Dialogs.
     */
    public HBCIMessageQueue getMessageQueue()
    {
        return this.queue;
    }
    
    /**
     * Erzeugt explizit eine neue Message.
     */
    public void newMsg()
    {
        HBCIUtils.log("starting new message",HBCIUtils.LOG_DEBUG);
        this.queue.append(new HBCIMessage());
        listOfGVs.clear();
    }
    
}
