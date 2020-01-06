<template>
    <div>

                        
<h4>Составление слов из букв и других слов</h4>

<div class="alert alert-secondary well" role="alert">
    <p>
        <span>Введите слово или последовательность букв, из которых нужно составить слова</span>
    </p>
    <form class="form-inline" method="get" action="/answers">
        <div class="form-group">
            <input type="text" class="form-control form-control-lg margin-right" placeholder="" name="word" value="" maxlength="30" autocomplete="off">
        </div>
        <button class="btn btn-primary btn-lg" id="start_search" type="submit">Искать варианты</button>
    </form>
</div>


<div id="myModalWaiting" class="modal fade">
    <div class="modal-dialog">
        <div class="modal-content">
            <p class="text-center" style="padding: 40px 0px 30px 0px;">
                <img src="@/assets/images/waiting.gif" width="40" border="0">
                <strong>Идет поиск комбинаций слов ...</strong>
            </p>
        </div>
    </div>
</div> 

    

    </div>
</template>

<script>
export default {
    
}
</script>